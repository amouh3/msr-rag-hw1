package edu.uic.msr

import com.typesafe.config.ConfigFactory
import edu.uic.msr.ollama.Ollama
import scala.io.Source
import scala.util.Try
import org.slf4j.LoggerFactory

/**
 * SearchSmoke:
 *  - Loads the CSV index produced by IndexAll (doc,chunk_idx,chars,vec)
 *  - Embeds a query and returns the top-k cosine matches
 *
 * Logging:
 *  - INFO: config snapshot, row count, query/model
 *  - WARN: empty query embedding
 */
object SearchSmoke {

  private val log = LoggerFactory.getLogger(getClass)

  final case class Row(doc: String, chunkIdx: Int, chars: Int, vec: Array[Float])

  private def parseRow(line: String): Option[Row] = {
    // CSV row from IndexAll: "doc",chunk_idx,chars,"v0 v1 v2 ..."
    val parts = line.split(",", 4)
    if (parts.length != 4) return None

    val doc  = parts(0).stripPrefix("\"").stripSuffix("\"")
    val idx  = Try(parts(1).toInt).getOrElse(-1)
    val ch   = Try(parts(2).toInt).getOrElse(0)
    val vs   = parts(3).stripPrefix("\"").stripSuffix("\"")
      .split("\\s+").flatMap(s => Try(s.toFloat).toOption)

    Some(Row(doc, idx, ch, vs))
  }

  private def cosine(a: Array[Float], b: Array[Float]): Double = {
    if (a.isEmpty || b.isEmpty || a.length != b.length) return 0.0
    val dot = a.indices.map(i => a(i) * b(i)).sum.toDouble
    val na  = math.sqrt(a.map(x => x*x).sum.toDouble)
    val nb  = math.sqrt(b.map(x => x*x).sum.toDouble)
    if (na == 0 || nb == 0) 0.0 else dot / (na * nb)
  }

  def main(args: Array[String]): Unit = {
    // Usage: sbt "runMain edu.uic.msr.SearchSmoke --conf conf/local.conf --q 'your query' --k 10"
    val confPath =
      if (args.contains("--conf")) args(args.indexOf("--conf") + 1) else "conf/local.conf"
    val query    =
      if (args.contains("--q"))    args(args.indexOf("--q")    + 1) else "android permissions"
    val k        =
      if (args.contains("--k"))    Try(args(args.indexOf("--k") + 1).toInt).getOrElse(10) else 10

    val cfg   = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val index = cfg.getString("index.out")
    val model = cfg.getString("embed.model")

    println(s"Loading index: $index")
    val rows = Source.fromFile(index).getLines().drop(1).flatMap(parseRow).toVector
    println(s"Rows loaded: ${rows.size}")
    log.info("SearchSmoke: conf='{}', model='{}', rows={}, k={}, q='{}'",
      confPath, model, Int.box(rows.size), Int.box(k), query)

    println(s"Embedding query with '$model': $query")
    val qvOpt = Ollama.embed(Vector(query), model).headOption
    if (qvOpt.isEmpty) {
      log.warn("Empty embedding returned for query='{}'", query)
      println("Failed to embed query (empty vector)"); return
    }
    val qv = qvOpt.get

    val top = rows
      .map(r => r -> cosine(qv, r.vec))
      .sortBy(-_._2)
      .take(k)

    println("\nTop matches:")
    top.zipWithIndex.foreach { case ((r, score), i) =>
      println(f"${i+1}%2d. score=${score}%.4f  doc=${r.doc}  chunk=${r.chunkIdx}%d  chars=${r.chars}%d")
    }
  }
}
