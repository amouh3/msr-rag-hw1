package edu.uic.msr.ollama

import sttp.client3._
import sttp.client3.circe._
import io.circe._
import io.circe.generic.auto._
import sttp.model.Uri

object Ollama {

  private val base: Uri = uri"http://127.0.0.1:11434"
  private val be       = HttpURLConnectionBackend()
  private val eurl     = uri"$base/api/embeddings"

  // ----- request/response models -----
  // Your Ollama returns {"embedding": [...]} when we send {"prompt": "..."}.
  final case class SingleEmbedReq(model: String, prompt: String)
  final case class SingleEmbedResp(embedding: Vector[Float])

  // Fallback shape some builds use: {"embeddings": [[...], ...]} for {"input": ["..."]}
  final case class MultiEmbedReq(model: String, input: Vector[String])
  final case class MultiEmbedResp(embeddings: Vector[Vector[Float]])

  // ----- public API -----
  def embed(texts: Vector[String], model: String): Vector[Array[Float]] =
    texts.map(t => singleEmbed(t, model).toArray)

  def embeddingDim(model: String): Int =
    singleEmbed("hello", model).length

  // ----- internals -----
  private def singleEmbed(text: String, model: String): Vector[Float] = {
    // Preferred path on your machine: prompt -> embedding
    val req1 = basicRequest
      .post(eurl)
      .body(SingleEmbedReq(model = model, prompt = text))   // circe body
      .response(asJson[SingleEmbedResp])

    req1.send(be).body match {
      case Right(ok) if ok.embedding.nonEmpty =>
        ok.embedding

      case _ =>
        // Fallback: input(array) -> embeddings
        val req2 = basicRequest
          .post(eurl)
          .body(MultiEmbedReq(model = model, input = Vector(text))) // typed body
          .response(asJson[MultiEmbedResp])

        req2.send(be).body match {
          case Right(m) if m.embeddings.nonEmpty => m.embeddings.head
          case _                                 => Vector.empty
        }
    }
  }
}
