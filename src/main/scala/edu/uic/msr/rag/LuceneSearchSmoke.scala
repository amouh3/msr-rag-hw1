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

/**
 * Smoke test for Lucene vector search over built shard directories.
 *
 * Flow:
 *  1) Read minimal config (mr.outputDir, embed.model).
 *  2) Resolve shard directories named index_shard_* under outputDir.
 *  3) Embed the query with Ollama.
 *  4) Search each shard with KnnFloatVectorQuery, gather hits, merge top-K.
 *
 * Logs:
 *  - INFO: config snapshot, shard count, final merged count
 *  - DEBUG: per-shard timings and hit counts
 *  - ERROR: unexpected empty embedding
 *
 * NOTE: Keeps your println output of the final Top-K table.
 */
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

    log.info("LuceneSearchSmoke: conf='{}', root='{}', model='{}', k={}, q='{}'",
      confPath, rootOut, model, Int.box(k), query)

    val qvec = {
      val t0 = System.nanoTime()
      val v = Ollama.embed(Vector(query), model).headOption.getOrElse {
        log.error("Empty embedding from Ollama for query='{}'", query)
        throw new RuntimeException("Empty embedding from Ollama")
      }
      val dt = (System.nanoTime() - t0) / 1e6
      log.info("Embedded query (dim={}, ~{} ms)", Int.box(v.length), Double.box(dt))
      v
    }

    val shards = listShardDirs(rootOut)
    log.info("Found {} shard dir(s) under {}", Int.box(shards.size), rootOut)
    require(shards.nonEmpty, s"No shard dirs under $rootOut")

    // Search each shard
    case class Hit(score: Float, docId: String, chunkId: String, text: String)
    val allHits =
      shards.flatMap { p =>
        val t0 = System.nanoTime()
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
        val dt = (System.nanoTime() - t0) / 1e6
        log.debug("Shard {}: {} hit(s) in ~{} ms", p.getFileName.toString, Int.box(hits.size), Double.box(dt))
        hits
      }

    // Merge top-k globally
    val merged = allHits.sortBy(h => -h.score).take(k)
    log.info("Merged global topK: {}", Int.box(merged.size))

    println(s"\nTop $k results:")
    merged.zipWithIndex.foreach { case (h, i) =>
      println(f"${i+1}%2d. score=${h.score}%.4f  doc=${h.docId} chunk=${h.chunkId}  text=${h.text}")
    }
  }
}
