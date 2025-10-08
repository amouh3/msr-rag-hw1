package edu.uic.msr.rag

import scala.io.Source
import io.circe._, io.circe.parser._
import org.slf4j.LoggerFactory

/** Minimal chunk index: one JSON object per line:
 * {"id":"doc#chunk#123","text":"...","embedding":[0.1, -0.2, ...]}
 *
 * Notes:
 * - `loadJsonl` is tolerant: skips blank lines and lines that fail to parse or miss required fields.
 * - Cosine similarity and topK helpers are pure functions.
 */
final case class Chunk(id: String, text: String, embedding: Vector[Double])

object SimpleIndex {
  private val log = LoggerFactory.getLogger(getClass)

  /** Load a JSONL file of chunks into memory.
   * Keeps behavior identical: silently skips malformed lines.
   */
  def loadJsonl(path: String): Vector[Chunk] = {
    log.info("SimpleIndex.loadJsonl: path='{}'", path)
    val src = Source.fromFile(path, "UTF-8")
    try {
      var parsed = 0
      var skipped = 0
      val vec = src.getLines().flatMap { line =>
        if (line.trim.isEmpty) { skipped += 1; None }
        else {
          parse(line).toOption.flatMap { js =>
            val c = js.hcursor
            val r = for {
              id   <- c.get[String]("id").toOption
              text <- c.get[String]("text").toOption
              emb  <- c.get[Vector[Double]]("embedding").toOption
            } yield {
              parsed += 1
              Chunk(id, text, emb)
            }
            if (r.isEmpty) skipped += 1
            r
          }
        }
      }.toVector
      log.info("SimpleIndex.loadJsonl: loaded={} skipped={}", Int.box(parsed), Int.box(skipped))
      vec
    } finally src.close()
  }

  /** Cosine similarity.
   * Returns 0.0 if either vector has zero norm.
   */
  def cosine(a: Vector[Double], b: Vector[Double]): Double = {
    val dot = a.view.zip(b).map { case (x,y) => x*y }.sum
    val na  = math.sqrt(a.view.map(x => x*x).sum)
    val nb  = math.sqrt(b.view.map(x => x*x).sum)
    if (na == 0 || nb == 0) 0.0 else dot / (na * nb)
  }

  /** Return top-k (Chunk, score) by cosine similarity.
   * Ties are left in the order produced by sortBy on (-score).
   */
  def topK(emb: Vector[Double], chunks: Vector[Chunk], k: Int): Vector[(Chunk, Double)] = {
    // Small DEBUG summary; avoid per-item logging to keep it fast.
    // log.debug("SimpleIndex.topK: corpus={}, k={}", Int.box(chunks.size), Int.box(k))
    chunks.iterator
      .map(c => c -> cosine(emb, c.embedding))
      .toVector
      .sortBy(-_._2)
      .take(k)
  }
}
