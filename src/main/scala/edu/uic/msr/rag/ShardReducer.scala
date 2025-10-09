package edu.uic.msr.rag

import org.apache.hadoop.io.{IntWritable, Text}
import org.apache.hadoop.mapreduce.Reducer
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.VectorSimilarityFunction
import org.slf4j.LoggerFactory
import io.circe.parser._
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

/**
 * ShardReducer — Conceptual Overview (Option 1: Map/Reduce on text corpus)
 * -----------------------------------------------------------------------------
 * Goal of the reducer stage:
 *   - For a given shard key, collect all chunk JSON records produced by mappers
 *   - Build a shard-local Lucene index *locally* (fast I/O, single writer)
 *   - Copy the completed index to the configured destination filesystem
 *   - Emit a small marker line (shard -> path) for sanity checks/kick-the-tires queries
 *
 * Reduce input (K2, V2):
 *   - K2: IntWritable   -> shard id (partition) chosen by the mapper
 *   - V2: Text          -> one JSON record per chunk: {doc_id, chunk_id, text, hash, ts, vec}
 *
 * Reduce output (K3, V3):
 *   - K3: Text          -> "shard_<id>"
 *   - K3: Text          -> "<dest path to shard index>"
 *   This is not used for indexing itself, but is extremely helpful for validation and
 *   for simple scripts that want to enumerate the produced shard locations.
 *
 * Why build locally then copy?
 *   - Lucene's IndexWriter prefers local file semantics and benefits from fast local I/O.
 *     Building locally avoids remote FS latency and then we perform a single atomic copy.
 *   - The destination FileSystem is resolved from the path (HDFS, S3A, etc.), making the
 *     reducer portable across local/HDFS/S3 deployments without code changes.
 *
 * Lucene field choices:
 *   - "text" is stored with TextField for full-text search/analyzer pipeline.
 *   - "vec" is stored with KnnFloatVectorField and COSINE similarity for ANN search.
 *   - StringField for doc_id/hash/chunk_id/ts enables exact matching and debugging.
 *
 * Logging intent:
 *   - INFO   -> where shard index is written and where it is copied
 *   - WARN   -> bad/malformed JSON or best-effort cleanup failures
 *   - ERROR  -> copy failures (these indicate an output FS or permission problem)
 *
 * Resulting artifact layout:
 *   - <msr.output.dir>/index_shard_<N>   (per reducer N)
 *   which matches the AskLucene consumer's expectation when pointing at multiple shards.
 */
class ShardReducer extends Reducer[IntWritable, Text, Text, Text] {
  private val log   = LoggerFactory.getLogger(getClass)
  private val outK  = new Text()
  private val outV  = new Text()

  override def reduce(key: IntWritable, values: java.lang.Iterable[Text],
                      ctx: Reducer[IntWritable, Text, Text, Text]#Context): Unit = {
    val conf     = ctx.getConfiguration
    val shardId  = key.get
    // msr.output.dir is intentionally configurable so experiments can write to:
    //  - local FS (for unit/integration tests)
    //  - HDFS on EMR (hdfs:///...)
    //  - S3A on EMR (s3a://bucket/...)
    val outRoot  = conf.get("msr.output.dir", "hdfs:///user/hadoop/index_shards_mr_out")

    // ---- 1) Build Lucene index in a local temp dir (fast local IO) ----
    // Using a temp dir ensures task isolation: concurrent reducers cannot trample
    // each other's IndexWriter state. The local FS also keeps build/commit fast.
    val localDir = Files.createTempDirectory(s"lucene-shard-$shardId")
    val dir      = FSDirectory.open(localDir)
    val iwc      = new IndexWriterConfig(new StandardAnalyzer())
      .setOpenMode(IndexWriterConfig.OpenMode.CREATE) // always create a fresh shard
    val iw       = new IndexWriter(dir, iwc)

    log.info("Reducer[{}]: writing Lucene index locally at {}", Int.box(shardId), localDir.toString)

    try {
      // Iterate the JSON lines produced by the mappers.
      // Each value corresponds to exactly one chunk/embedding. We parse defensively:
      // a single malformed line should not fail the entire shard build; instead we
      // log it and proceed (best-effort robustness).
      values.asScala.foreach { t =>
        val line = t.toString
        parse(line) match {
          case Left(err) =>
            // Bad JSON can stem from upstream corruption or rare extraction artifacts.
            // Logging the line helps trace back problem documents during grading/demos.
            log.warn("Reducer[{}]: bad JSON: {} // line={}", Int.box(shardId), err, line)
          case Right(js) =>
            val c     = js.hcursor
            val docId = c.get[String]("doc_id").getOrElse("NA")
            val chunk = c.get[Int]("chunk_id").getOrElse(-1)
            val text  = c.get[String]("text").getOrElse("")
            val hash  = c.get[String]("hash").getOrElse("")
            val ts    = c.get[Long]("ts").map(_.toString).getOrElse("")
            val vec   = c.get[Vector[Float]]("vec").map(_.toArray).getOrElse(Array.emptyFloatArray)

            // Construct a Lucene document:
            //  - StringField (stored) keeps provenance fields exact and retrievable
            //  - TextField    allows analysis for future keyword/bm25 usage
            //  - KnnFloatVectorField registers the dense vector under "vec" for ANN
            val d = new Document()
            d.add(new StringField("doc_id",   docId,                    Field.Store.YES))
            d.add(new StringField("chunk_id", chunk.toString,           Field.Store.YES))
            d.add(new TextField  ("text",     text,                     Field.Store.YES))
            d.add(new StringField("hash",     hash,                     Field.Store.YES))
            d.add(new StringField("ts",       ts,                       Field.Store.YES))
            if (vec.nonEmpty) {
              // COSINE is chosen to align with common sentence-embedding practices.
              // If a different model were used (e.g., dot-product optimized), this
              // would be the knob to revisit — but the reducer *does not* compute
              // similarity; it only stores vectors for query-time retrieval.
              d.add(new KnnFloatVectorField("vec", vec, VectorSimilarityFunction.COSINE))
            }
            iw.addDocument(d)
        }
      }
      // Commit once per shard to amortize fsync. This also makes it clear in logs
      // where the boundary is between "index built" and "copy to destination".
      iw.commit()
    } finally {
      // Close resources even if iteration threw; guarantees well-formed on-disk index.
      iw.close()
      dir.close()
    }

    // ---- 2) Copy the local directory to the target FS: <outRoot>/index_shard_<shardId> ----
    // Resolving the FileSystem from the *destination Path* allows the same code to
    // write to HDFS, S3A, or local FS depending on msr.output.dir (no code change).
    val destRoot = new Path(outRoot)
    val outFs: FileSystem = destRoot.getFileSystem(conf)  // resolve FS from the destination path
    val destDir  = new Path(destRoot, s"index_shard_$shardId")

    if (!outFs.exists(destRoot)) outFs.mkdirs(destRoot)

    log.info("Reducer[{}]: copying local index to {}", Int.box(shardId), destDir.toString)
    val ok = FileUtil.copy(localDir.toFile, outFs, destDir, true, conf)
    if (!ok) {
      // Copy failure typically indicates permissions or a wrong FS URI. This is a
      // terminal error for this shard's publish step (the local build *did* succeed).
      log.error("Reducer[{}]: FAILED to copy {} -> {}", Int.box(shardId), localDir.toString, destDir.toString)
    } else {
      log.info("Reducer[{}]: copied index to {}", Int.box(shardId), destDir.toString)
    }

    // ---- 3) Emit a marker line into MR part files (handy for sanity checks) ----
    // This lightweight (K,V) enables quick “enumerate shards” checks without listing
    // the filesystem. It also makes unit tests simpler (assert emitted paths).
    outK.set(s"shard_$shardId")
    outV.set(destDir.toString)
    ctx.write(outK, outV)

    // ---- 4) Best-effort cleanup of local temp dir ----
    // We attempt to remove the temp directory even if the copy failed, to avoid
    // leaking disk space on reducer nodes. Failures are non-fatal and logged.
    try {
      Files.walk(localDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
    } catch {
      case e: Throwable =>
        log.warn("Reducer[{}]: temp cleanup failed for {}: {}", Int.box(shardId), localDir.toString, e.getMessage)
    }
  }
}
