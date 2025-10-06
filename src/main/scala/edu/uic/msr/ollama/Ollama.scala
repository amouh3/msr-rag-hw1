package edu.uic.msr.ollama

import sttp.client3._
import sttp.client3.circe._
import sttp.model.Uri
import io.circe._
import io.circe.generic.auto._
import scala.concurrent.duration._

/** Thin, blocking client for Ollama /api/embeddings with batching + fallback.
 * Base URL is injected (so EMR mappers can hit a private EC2 IP).
 */
final class OllamaClient(baseUrl: String, connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 60000) {
  private val backend: SttpBackend[Identity, Any] =
    HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(connectTimeoutMs.millis))

  // Build URIs from the constructor-provided base URL
  private val baseUri: Uri = Uri.unsafeParse(baseUrl)                // or: uri"$baseUrl" if you prefer the interpolator
  private val embedUri: Uri = baseUri.addPath("api", "embeddings")

  // --- request/response models ---
  final case class SingleEmbedReq(model: String, prompt: String)
  final case class SingleEmbedResp(embedding: Vector[Float])

  final case class MultiEmbedReq(model: String, input: Vector[String])
  final case class MultiEmbedResp(embeddings: Vector[Vector[Float]])

  /** Embed one text. Tries {"prompt": "..."} first, falls back to {"input": ["..."]}. */
  def embedOne(text: String, model: String): Vector[Float] = {
    val req1 = basicRequest
      .post(embedUri)
      .readTimeout(readTimeoutMs.millis)
      .body(SingleEmbedReq(model = model, prompt = text))
      .response(asJson[SingleEmbedResp])

    req1.send(backend).body match {
      case Right(ok) if ok.embedding.nonEmpty => ok.embedding
      case _ =>
        val req2 = basicRequest
          .post(embedUri)
          .readTimeout(readTimeoutMs.millis)
          .body(MultiEmbedReq(model = model, input = Vector(text)))
          .response(asJson[MultiEmbedResp])

        req2.send(backend).body match {
          case Right(m) if m.embeddings.nonEmpty => m.embeddings.head
          case _                                 => Vector.empty
        }
    }
  }

  /** Embed many texts with simple batching and retry. */
  def embedBatch(texts: Vector[String], model: String, batch: Int = 16, retries: Int = 2): Vector[Array[Float]] = {
    def withRetry[A](n: Int)(thunk: => A): A =
      try thunk
      catch {
        case _: Exception if n > 0 =>
          Thread.sleep(300L * (retries - n + 1))
          withRetry(n - 1)(thunk)
      }

    texts.grouped(batch).toVector.flatMap { group =>
      // Prefer multi-input when batching
      val req = basicRequest
        .post(embedUri)
        .readTimeout(readTimeoutMs.millis)
        .body(MultiEmbedReq(model = model, input = group))
        .response(asJson[MultiEmbedResp])

      val tryMulti: Either[Throwable, Vector[Vector[Float]]] =
        withRetry(retries) {
          req.send(backend).body match {
            case Right(multi) => Right(multi.embeddings)
            case Left(err)    => Left(new RuntimeException(err.getMessage))
          }
        }

      val vecs: Vector[Vector[Float]] = tryMulti match {
        case Right(vs) if vs.size == group.size => vs
        case _                                   => group.map(t => withRetry(retries) { embedOne(t, model) })
      }

      vecs.map(_.toArray)
    }
  }
}

/** Helper to construct a client with env-friendly defaulting,
 * plus legacy static-style helpers for older call sites.
 */
object Ollama {
  /** Decide base URL:
   *   1) explicit parameter if provided
   *   2) env OLLAMA_HOST
   *   3) default http://127.0.0.1:11434
   */
  def client(baseOpt: Option[String] = None): OllamaClient = {
    val url = baseOpt
      .orElse(sys.env.get("OLLAMA_HOST"))
      .getOrElse("http://127.0.0.1:11434")
    new OllamaClient(url)
  }

  // -------- Legacy convenience wrappers (used elsewhere in your project) --------

  /** Old call-site compatibility: Ollama.embed(texts, model) */
  def embed(texts: Vector[String], model: String, batch: Int = 16): Vector[Array[Float]] =
    client().embedBatch(texts, model, batch)

  /** Old call-site compatibility: used by smoke tools */
  def embeddingDim(model: String): Int =
    client().embedOne("hello", model).length
}
