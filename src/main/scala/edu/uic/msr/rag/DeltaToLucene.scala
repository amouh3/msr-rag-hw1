package edu.uic.msr.rag

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.document.{Document, StoredField, StringField, TextField, Field}
import org.apache.lucene.analysis.standard.StandardAnalyzer
//import org.apache.lucene.codecs.lucene95.Lucene95HnswVectorsFormat
//import org.apache.lucene.codecs.lucene95.Lucene95Codec
//import org.apache.lucene.codecs.FilterCodec
import org.apache.lucene.document.KnnFloatVectorField
import java.nio.file.{Files, Paths}
import edu.uic.msr.pdf.Pdfs // not used here but keeps symmetry with project
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

/**
 * DeltaToLucene:
 *  - Reads Delta `docs` table
 *  - Chunks text and embeds with Ollama
 *  - Writes per-shard Lucene kNN indexes:
 *      fields: "text"(stored), "doc_id"(stored), "chunk_id"(stored), "vec"(KnnFloatVectorField)
 *  - Sharding: hash(docId) % shards
 *
 * Config keys (application.conf):
 *   paths.deltaRoot     e.g. "var/delta"
 *   tables.docs         e.g. "docs"
 *   index.outRoot       e.g. "var/lucene"
 *   index.shards        e.g. 4
 *   embed.model         e.g. "nomic-embed-text"
 *   embed.maxChars      (optional, default 800)
 *   embed.overlap       (optional, default 160)
 */
object DeltaToLucene {
  private val log = LoggerFactory.getLogger(getClass)

  private def ensureDir(p: String): Unit = {
    val path = Paths.get(p)
    if (!Files.exists(path)) Files.createDirectories(path)
  }



  def main(args: Array[String]): Unit = {
    val cfg         = ConfigFactory.load()
    val deltaRoot   = cfg.getString("paths.deltaRoot")
    val docsTable   = cfg.getString("tables.docs")
    val outRoot     = cfg.getString("index.outRoot")
    val shards      = cfg.getInt("index.shards")
    val model       = cfg.getString("embed.model")
    val maxChars    = if (cfg.hasPath("embed.maxChars")) cfg.getInt("embed.maxChars") else 800
    val overlap     = if (cfg.hasPath("embed.overlap"))  cfg.getInt("embed.overlap")  else 160

    log.info(s"DeltaToLucene: deltaRoot=$deltaRoot table=$docsTable outRoot=$outRoot shards=$shards model=$model maxChars=$maxChars overlap=$overlap")
    sys.env.get("OLLAMA_HOST").foreach(h => log.info(s"OLLAMA_HOST=$h"))

    val spark = SparkSession.builder()
      .appName("DeltaToLucene")
      .master(Option(System.getProperty("spark.master")).getOrElse("local[*]"))
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")
    import spark.implicits._

    val docsPath = s"$deltaRoot/$docsTable"
    val df = spark.read.format("delta").load(docsPath)
      .select($"docId", $"uri", $"text")

    // Bring to driver; for larger corpora you’d do partitioned writes per shard.
    val rows = df.as[(String, String, String)].collect().toVector
    log.info(s"Loaded ${rows.size} docs from Delta")

    // Prepare per-shard writers
    ensureDir(outRoot)
    val analyzer = new StandardAnalyzer()
    val codec = org.apache.lucene.codecs.Codec.getDefault()


    val writers: Vector[IndexWriter] = (0 until shards).toVector.map { shard =>
      val dir = s"$outRoot/index_shard_$shard"
      ensureDir(dir)
      val iwc = new IndexWriterConfig(analyzer)
      iwc.setCodec(codec)
      iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE) // overwrite shard each run
      new IndexWriter(FSDirectory.open(Paths.get(dir)), iwc)
    }

    def shardOf(id: String): Int = (id.hashCode & 0x7fffffff) % shards

    var totalChunks = 0
    var totalEmbedded = 0

    try {
      rows.foreach { case (docId, uri, text) =>
        val chunks = edu.uic.msr.chunk.Chunker.chunks(text, maxChars = maxChars, overlap = overlap)
        chunks.zipWithIndex.grouped(16).foreach { batch => // small batches to Ollama
          val texts = batch.map(_._1).toVector
          if (texts.nonEmpty) {
            val vectors = Ollama.embed(texts, model) // Vector[Vector[Double]] or Float; your helper returns doubles—map to float
            val shardIdx = shardOf(docId)
            val w = writers(shardIdx)
            vectors.zip(batch).foreach { case (vecD, (chunkText, i)) =>
              val vec: Array[Float] = vecD.map(_.toFloat).toArray
              val doc = new Document()
              doc.add(new TextField("text",   chunkText, Field.Store.YES))
              doc.add(new StringField("doc_id",  docId, Field.Store.YES))
              doc.add(new StringField("chunk_id", i.toString, Field.Store.YES))
              doc.add(new KnnFloatVectorField("vec", vec))
              w.addDocument(doc)
              totalEmbedded += 1
            }
          }
          totalChunks += batch.size
        }
      }

      writers.foreach(_.commit())
      log.info(s"Indexed $totalEmbedded chunks (from ~$totalChunks prepared) across $shards shard(s) at $outRoot")
      println(s"OK: Lucene index built. outRoot=$outRoot shards=$shards chunks=$totalEmbedded")
    } finally {
      writers.foreach(_.close())
      spark.stop()
    }
  }
}
