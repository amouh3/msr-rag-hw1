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

/** For each shard key, writes a Lucene index locally, then copies to HDFS:
 *   <msr.outputDir>/index_shard_<N>
 */
class ShardReducer extends Reducer[IntWritable, Text, Text, Text] {
  private val log   = LoggerFactory.getLogger(getClass)
  private val outK  = new Text()
  private val outV  = new Text()

  override def reduce(key: IntWritable, values: java.lang.Iterable[Text],
                      ctx: Reducer[IntWritable, Text, Text, Text]#Context): Unit = {
    val conf     = ctx.getConfiguration
    val shardId  = key.get
    val hOutRoot = conf.get("msr.output.dir", "hdfs:///msr/index_shards")

    // 1) build index in a local temp dir (fast)
    val localDir = Files.createTempDirectory(s"lucene-shard-$shardId")
    val dir      = FSDirectory.open(localDir)
    val iwc      = new IndexWriterConfig(new StandardAnalyzer())
    val iw       = new IndexWriter(dir, iwc)

    log.info(s"Reducer[$shardId]: writing Lucene index locally at $localDir")

    try {
      values.forEach { t =>
        val line = t.toString
        parse(line) match {
          case Left(err) =>
            log.warn(s"Reducer[$shardId]: bad JSON: $err")
          case Right(js) =>
            val c = js.hcursor
            val docId  = c.get[String]("doc_id").getOrElse("NA")
            val chunk  = c.get[Int]("chunk_id").getOrElse(-1)
            val text   = c.get[String]("text").getOrElse("")
            val vec    = c.get[Vector[Float]]("vec").map(_.toArray).getOrElse(Array.emptyFloatArray)

            val d = new Document()
            d.add(new StringField("doc_id",   docId,               Field.Store.YES))
            d.add(new StringField("chunk_id", chunk.toString,      Field.Store.YES))
            d.add(new TextField  ("text",     text,                Field.Store.YES))
            d.add(new KnnFloatVectorField("vec", vec, VectorSimilarityFunction.COSINE))
            iw.addDocument(d)
        }
      }
      iw.commit()
    } finally {
      iw.close()
      dir.close()
    }

    // 2) copy the local directory to HDFS: <hOutRoot>/index_shard_<shardId>
    val hConf    = new org.apache.hadoop.conf.Configuration()
    val hfs      = FileSystem.get(hConf)
    val destRoot = new Path(hOutRoot)
    val destDir  = new Path(destRoot, s"index_shard_$shardId")

    // make sure root exists
    if (!hfs.exists(destRoot)) hfs.mkdirs(destRoot)

    // Hadoop util: copy directory tree (recursive)
    val ok = FileUtil.copy(localDir.toFile, hfs, destDir, true, hConf)
    if (!ok) {
      log.error(s"Reducer[$shardId]: FAILED to copy $localDir -> $destDir")
    } else {
      log.info(s"Reducer[$shardId]: copied index to $destDir")
    }

    // Optional “marker” so you can see something in MR output
    outK.set(s"shard_$shardId")
    outV.set(destDir.toString)
    ctx.write(outK, outV)
  }
}
