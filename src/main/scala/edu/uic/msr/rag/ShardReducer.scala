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

/** For each shard key, writes a Lucene index locally, then copies to FS:
 *   <msr.output.dir>/index_shard_<N>
 * Set msr.output.dir in your conf, e.g.
 *   - hdfs:///user/hadoop/index_shards_mr_out
 *   - s3a://your-bucket/outputs/index_shards_mr_out
 *
 * IMPORTANT: we resolve the FileSystem from the *destination Path* so either HDFS or S3A works.
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

    log.info(s"Reducer[$shardId]: writing Lucene index locally at $localDir")

    try {
      // Iterate the JSON lines produced by the mappers
      values.asScala.foreach { t =>
        val line = t.toString
        parse(line) match {
          case Left(err) =>
            log.warn(s"Reducer[$shardId]: bad JSON: $err // line=$line")
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
    val outFs: FileSystem = destRoot.getFileSystem(conf)  // <-- the crucial fix
    val destDir  = new Path(destRoot, s"index_shard_$shardId")

    if (!outFs.exists(destRoot)) outFs.mkdirs(destRoot)

    log.info(s"Reducer[$shardId]: copying local index to $destDir")
    val ok = FileUtil.copy(localDir.toFile, outFs, destDir, true, conf)
    if (!ok) {
      log.error(s"Reducer[$shardId]: FAILED to copy $localDir -> $destDir")
    } else {
      log.info(s"Reducer[$shardId]: copied index to $destDir")
    }

    // ---- 3) Emit a marker line into MR part files (handy for sanity checks) ----
    outK.set(s"shard_$shardId")
    outV.set(destDir.toString)
    ctx.write(outK, outV)

    // ---- 4) Best-effort cleanup of local temp dir ----
    try {
      // Recursively delete the temp dir
      Files.walk(localDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
    } catch {
      case e: Throwable => log.warn(s"Reducer[$shardId]: temp cleanup failed for $localDir: ${e.getMessage}")
    }
  }
}
