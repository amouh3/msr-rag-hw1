package edu.uic.msr.rag

import com.typesafe.config.ConfigFactory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{IndexSearcher, TopDocs}
import org.apache.lucene.document.Document
import org.apache.lucene.search.KnnFloatVectorQuery
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory
import edu.uic.msr.ollama.Ollama

object LuceneSearchSmoke {
  private val log = LoggerFactory.getLogger(getClass)

  private def listShardDirs(root: String) =
    Files.list(Paths.get(root))
      .iterator().asScala
      .filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("index_shard_"))
      .toVector

  def main(args: Array[String]): Unit = {
    // args: --conf conf/local.conf --q "your question" --k 5
    def after(flag: String): Option[String] =
      args.sliding(2).collectFirst { case Array(f, v) if f == flag => v }

    val confPath = after("--conf").getOrElse("conf/local.conf")
    val cfg      = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val rootOut  = cfg.getString("mr.outputDir").stripPrefix("file:///")
    val model    = cfg.getString("embed.model")
    val k        = after("--k").flatMap(s => s.toIntOption).getOrElse(10)
    val query    = after("--q").getOrElse("android permissions")

    log.info(s"LuceneSearchSmoke: root=$rootOut  model=$model  k=$k  q=$query")

    val qvec = Ollama.embed(Vector(query), model).headOption.getOrElse {
      throw new RuntimeException("Empty embedding from Ollama")
    }

    val shards = listShardDirs(rootOut)
    require(shards.nonEmpty, s"No shard dirs under $rootOut")

    // Search each shard
    case class Hit(score: Float, docId: String, chunkId: String, text: String)
    val allHits =
      shards.flatMap { p =>
        val dir   = FSDirectory.open(p)
        val rdr   = DirectoryReader.open(dir)
        val srch  = new IndexSearcher(rdr)
        val knnQ  = new KnnFloatVectorQuery("vec", qvec, k)
        val top   = srch.search(knnQ, k)

        val hits  = top.scoreDocs.toVector.map { sd =>
          val d: Document = srch.doc(sd.doc)
          Hit(
            score   = sd.score,
            docId   = d.get("doc_id"),
            chunkId = d.get("chunk_id"),
            text    = Option(d.get("text")).getOrElse("").take(200)
          )
        }
        rdr.close(); dir.close()
        hits
      }

    // Merge top-k globally
    val merged = allHits.sortBy(h => -h.score).take(k)

    println(s"\nTop $k results:")
    merged.zipWithIndex.foreach { case (h, i) =>
      println(f"${i+1}%2d. score=${h.score}%.4f  doc=${h.docId} chunk=${h.chunkId}  text=${h.text}")
    }
  }
}
