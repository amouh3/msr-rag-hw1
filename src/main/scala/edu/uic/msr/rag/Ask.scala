package edu.uic.msr.rag

import com.typesafe.config.ConfigFactory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery, ScoreDoc}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._
import edu.uic.msr.ollama.Ollama

object Ask {
  private val log = LoggerFactory.getLogger(getClass)
  private val VEC = "vec"

  final case class Hit(score: Float, docId: String, chunkId: String, text: String)

  def main(args: Array[String]): Unit = {
    val confPath =
      if (args.contains("--conf")) args(args.indexOf("--conf") + 1) else "conf/local.conf"

    val qArg   = args.sliding(2,1).find(a => a.headOption.contains("--q")).flatMap(_.lift(1)).getOrElse("")
    val kArg   = args.sliding(2,1).find(a => a.headOption.contains("--k")).flatMap(_.lift(1)).getOrElse("6")
    val k      = kArg.toIntOption.getOrElse(6)

    val cfg    = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val root   = cfg.getString("mr.outputDir")
    val model  = cfg.getString("embed.model")

    val rootPath = {
      val s = root.stripPrefix("file:///").stripPrefix("file://")
      Paths.get(s)
    }

    log.info(s"Ask: root=$rootPath  model=$model  k=$k  q='${qArg.take(120)}${if (qArg.length>120) "…"}'")

    if (!Files.exists(rootPath)) {
      System.err.println(s"Index root not found: $rootPath"); System.exit(2)
    }

    val shardPaths =
      Files.list(rootPath).iterator().asScala
        .filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("index_shard_"))
        .toVector
        .sortBy(_.getFileName.toString)

    if (shardPaths.isEmpty) {
      System.err.println(s"No shard directories under: $rootPath"); System.exit(2)
    }

    // OPEN: dirs -> readers -> searchers (so we can close in reverse order)
    val dirs:    Vector[FSDirectory]    = shardPaths.map(FSDirectory.open)
    val readers: Vector[DirectoryReader]= dirs.map(DirectoryReader.open)
    val searchers: Vector[IndexSearcher]= readers.map(new IndexSearcher(_))

    try {
      val qVec: Array[Float] =
        if (qArg.nonEmpty) Ollama.embed(Vector(qArg), model).head
        else {
          val fallbackQ = if (cfg.hasPath("search.query")) cfg.getString("search.query") else ""
          if (fallbackQ.isEmpty) { System.err.println("No query provided. Use --q or set search.query."); System.exit(1); Array.emptyFloatArray }
          else Ollama.embed(Vector(fallbackQ), model).head
        }

      val perShardHits: Vector[Array[ScoreDoc]] =
        searchers.map(_.search(new KnnFloatVectorQuery(VEC, qVec, k), k).scoreDocs)

      val hits = perShardHits.zip(searchers).flatMap { case (arr, s) =>
        arr.toSeq.map { sd =>
          val d = s.doc(sd.doc)
          Hit(
            score   = sd.score,
            docId   = Option(d.get("doc_id")).getOrElse("?"),
            chunkId = Option(d.get("chunk_id")).getOrElse("?"),
            text    = Option(d.get("text")).getOrElse("")
          )
        }
      }

      val top = hits.sortBy(-_.score).take(k)
      println(s"\nTop $k results:")
      top.zipWithIndex.foreach { case (h, i) =>
        val preview = h.text.replaceAll("\\s+"," ").take(180)
        println(f"${i+1}%2d. score=${h.score}%.4f  doc=${h.docId} chunk=${h.chunkId}  text=$preview")
      }

    } finally {
      // CLOSE in reverse: searchers (no close), then readers, then dirs
      readers.foreach(_.close())
      dirs.foreach(_.close())
    }
  }
}
