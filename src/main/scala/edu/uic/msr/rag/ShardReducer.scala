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
 * ShardReducer:
 *  - For each shard key (partition), builds a Lucene index locally, then copies it to the target FS:
 *      <msr.output.dir>/index_shard_<N>
 *  - Input values: JSON lines produced by mappers (doc_id, chunk_id, text, hash, ts, vec)
 *  - Output: emits (shard_N, <dest path>) as a marker line
 *
 * Logging:
 *  - INFO: where the local index is built, copy start/end
 *  - WARN: malformed JSON lines, best-effort cleanup failures
 *  - ERROR: failed copy
 *
 * Note:
 *  - Destination FileSystem is resolved from the *destination Path*, so HDFS and S3A both work.
 */
class ShardReducer extends Reducer[IntWritable, Text, Text, Text] {
  private val log   = LoggerFactory.getLogger(getClass)
  private val outK  = new Text()
  private val outV  = new Text()

  override def reduce(key: IntWritable, values: java.lang.Iterable[Text],
                      ctx: Reducer[IntWritable, Text, Text, Text]#Context): Unit = {
    val conf     = ctx.getConfiguration
    val shardId  = key.get
    val outRoot  = conf.get("msr.output.dir", "hdfs:///user/hadoop/index_shards_mr_out")

    // ---- 1) Build Lucene index in a local temp dir (fast local IO) ----
    val localDir = Files.createTempDirectory(s"lucene-shard-$shardId")
    val dir      = FSDirectory.open(localDir)
    val iwc      = new IndexWriterConfig(new StandardAnalyzer())
      .setOpenMode(IndexWriterConfig.OpenMode.CREATE) // always create a fresh shard
    val iw       = new IndexWriter(dir, iwc)

    log.info("Reducer[{}]: writing Lucene index locally at {}", Int.box(shardId), localDir.toString)

    try {
      // Iterate the JSON lines produced by the mappers
      values.asScala.foreach { t =>
        val line = t.toString
        parse(line) match {
          case Left(err) =>
            log.warn("Reducer[{}]: bad JSON: {} // line={}", Int.box(shardId), err, line)
          case Right(js) =>
            val c     = js.hcursor
            val docId = c.get[String]("doc_id").getOrElse("NA")
            val chunk = c.get[Int]("chunk_id").getOrElse(-1)
            val text  = c.get[String]("text").getOrElse("")
            val hash  = c.get[String]("hash").getOrElse("")
            val ts    = c.get[Long]("ts").map(_.toString).getOrElse("")
            val vec   = c.get[Vector[Float]]("vec").map(_.toArray).getOrElse(Array.emptyFloatArray)

            val d = new Document()
            d.add(new StringField("doc_id",   docId,                    Field.Store.YES))
            d.add(new StringField("chunk_id", chunk.toString,           Field.Store.YES))
            d.add(new TextField  ("text",     text,                     Field.Store.YES))
            d.add(new StringField("hash",     hash,                     Field.Store.YES))
            d.add(new StringField("ts",       ts,                       Field.Store.YES))
            if (vec.nonEmpty) {
              d.add(new KnnFloatVectorField("vec", vec, VectorSimilarityFunction.COSINE))
            }
            iw.addDocument(d)
        }
      }
      iw.commit()
    } finally {
      iw.close()
      dir.close()
    }

    // ---- 2) Copy the local directory to the target FS: <outRoot>/index_shard_<shardId> ----
    val destRoot = new Path(outRoot)
    val outFs: FileSystem = destRoot.getFileSystem(conf)  // resolve FS from the destination path
    val destDir  = new Path(destRoot, s"index_shard_$shardId")

    if (!outFs.exists(destRoot)) outFs.mkdirs(destRoot)

    log.info("Reducer[{}]: copying local index to {}", Int.box(shardId), destDir.toString)
    val ok = FileUtil.copy(localDir.toFile, outFs, destDir, true, conf)
    if (!ok) {
      log.error("Reducer[{}]: FAILED to copy {} -> {}", Int.box(shardId), localDir.toString, destDir.toString)
    } else {
      log.info("Reducer[{}]: copied index to {}", Int.box(shardId), destDir.toString)
    }

    // ---- 3) Emit a marker line into MR part files (handy for sanity checks) ----
    outK.set(s"shard_$shardId")
    outV.set(destDir.toString)
    ctx.write(outK, outV)

    // ---- 4) Best-effort cleanup of local temp dir ----
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
