package edu.uic.msr.rag

import scala.io.Source
import io.circe._, io.circe.parser._

/** Minimal chunk index: one JSON object per line:
 * {"id":"doc#chunk#123","text":"...","embedding":[0.1, -0.2, ...]}
 */
final case class Chunk(id: String, text: String, embedding: Vector[Double])

object SimpleIndex {
  def loadJsonl(path: String): Vector[Chunk] = {
    val src = Source.fromFile(path, "UTF-8")
    try {
      src.getLines().flatMap { line =>
        if (line.trim.isEmpty) None
        else {
          parse(line).toOption.flatMap { js =>
            val c = js.hcursor
            for {
              id   <- c.get[String]("id").toOption
              text <- c.get[String]("text").toOption
              emb  <- c.get[Vector[Double]]("embedding").toOption
            } yield Chunk(id, text, emb)
          }
        }
      }.toVector
    } finally src.close()
  }

  /** cosine similarity */
  def cosine(a: Vector[Double], b: Vector[Double]): Double = {
    val dot = a.view.zip(b).map { case (x,y) => x*y }.sum
    val na  = math.sqrt(a.view.map(x => x*x).sum)
    val nb  = math.sqrt(b.view.map(x => x*x).sum)
    if (na == 0 || nb == 0) 0.0 else dot / (na * nb)
  }

  /** Return top-k (id, text, score) by cosine similarity */
  def topK(emb: Vector[Double], chunks: Vector[Chunk], k: Int): Vector[(Chunk, Double)] = {
    chunks.iterator
      .map(c => c -> cosine(emb, c.embedding))
      .toVector
      .sortBy(-_._2)
      .take(k)
  }
}
