package edu.uic.msr.ollama

import sttp.client3._
import sttp.client3.circe._
import sttp.model.Uri
import io.circe._
import io.circe.generic.auto._
import scala.concurrent.duration._
import org.slf4j.LoggerFactory

/** Thin, blocking client for Ollama /api/embeddings with batching + fallback.
 * Base URL is injected (so EMR mappers can hit a private EC2 IP).
 *
 * Logging:
 * - INFO: client init, batch starts/finishes
 * - DEBUG: request mode (single vs multi), sizes, timing
 * - WARN: fallback paths and retries
 * - ERROR: empty/failed responses where applicable
 */
final class OllamaClient(baseUrl: String, connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 60000) {
  private val log = LoggerFactory.getLogger(getClass)

  private val backend: SttpBackend[Identity, Any] =
    HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(connectTimeoutMs.millis))

  // Build URIs from the constructor-provided base URL
  private val baseUri: Uri = {
    val u = Uri.unsafeParse(baseUrl) // or: uri"$baseUrl"
    log.info("OllamaClient: using baseUrl={} (connectTimeoutMs={}, readTimeoutMs={})",
      baseUrl, Int.box(connectTimeoutMs), Int.box(readTimeoutMs))
    u
  }
  private val embedUri: Uri = baseUri.addPath("api", "embeddings")

  // --- request/response models ---
  final case class SingleEmbedReq(model: String, prompt: String)
  final case class SingleEmbedResp(embedding: Vector[Float])

  final case class MultiEmbedReq(model: String, input: Vector[String])
  final case class MultiEmbedResp(embeddings: Vector[Vector[Float]])

  /** Embed one text. Tries {"prompt": "..."} first, falls back to {"input": ["..."]}. */
  def embedOne(text: String, model: String): Vector[Float] = {
    val t0 = System.nanoTime()

    val req1 = basicRequest
      .post(embedUri)
      .readTimeout(readTimeoutMs.millis)
      .body(SingleEmbedReq(model = model, prompt = text))
      .response(asJson[SingleEmbedResp])

    req1.send(backend).body match {
      case Right(ok) if ok.embedding.nonEmpty =>
        val dtMs = (System.nanoTime() - t0) / 1e6
        ok.embedding
      case other =>
        val t1 = System.nanoTime()
        val req2 = basicRequest
          .post(embedUri)
          .readTimeout(readTimeoutMs.millis)
          .body(MultiEmbedReq(model = model, input = Vector(text)))
          .response(asJson[MultiEmbedResp])

        req2.send(backend).body match {
          case Right(m) if m.embeddings.nonEmpty =>
            val dtMs = (System.nanoTime() - t1) / 1e6
            log.debug(s"embedOne: fallback(input[1]) succeeded, dim=${m.embeddings.head.length}, took ~${dtMs}ms")
            m.embeddings.head
          case fail =>
            Vector.empty
        }
    }
  }

  /** Embed many texts with simple batching and retry. */
  def embedBatch(texts: Vector[String], model: String, batch: Int = 16, retries: Int = 2): Vector[Array[Float]] = {
    log.info("embedBatch: count={}, batch={}, model={}, retries={}", Int.box(texts.length), Int.box(batch), model, Int.box(retries))

    def withRetry[A](n: Int)(thunk: => A): A =
      try thunk
      catch {
        case ex: Exception if n > 0 =>
          val attempt = retries - n + 1
          log.warn("embedBatch.withRetry: attempt {} failed: {} — retrying (remaining={})", Int.box(attempt), ex.getMessage, Int.box(n - 1))
          Thread.sleep(300L * (retries - n + 1))
          withRetry(n - 1)(thunk)
      }

    val grouped = texts.grouped(batch).toVector
    log.debug("embedBatch: groups={}", Int.box(grouped.size))

    val results = grouped.flatMap { group =>
      log.debug("embedBatch[group]: size={}", Int.box(group.size))
      // Prefer multi-input when batching
      val req = basicRequest
        .post(embedUri)
        .readTimeout(readTimeoutMs.millis)
        .body(MultiEmbedReq(model = model, input = group))
        .response(asJson[MultiEmbedResp])

      val start = System.nanoTime()
      val tryMulti: Either[Throwable, Vector[Vector[Float]]] =
        withRetry(retries) {
          req.send(backend).body match {
            case Right(multi) =>
              val dt = (System.nanoTime() - start) / 1e6
              log.debug(s"embedBatch[group]: multi-input success, returned=${multi.embeddings.size}, took ~${dt}ms")
              Right(multi.embeddings)
            case Left(err) =>
              Left(new RuntimeException(err.getMessage))
          }
        }

      val vecs: Vector[Vector[Float]] = tryMulti match {
        case Right(vs) if vs.size == group.size => vs
        case _ =>
          log.warn("embedBatch[group]: falling back to per-item embedOne for {} texts", Int.box(group.size))
          group.map { t =>
            withRetry(retries) {
              val e = embedOne(t, model)
              if (e.isEmpty) log.error("embedBatch[group]: embedOne returned empty embedding for len(text)={}", Int.box(t.length))
              e
            }
          }
      }

      vecs.map(_.toArray)
    }

    log.info("embedBatch(done): produced {} embeddings", Int.box(results.length))
    results
  }
}

/** Helper to construct a client with env-friendly defaulting,
 * plus legacy static-style helpers for older call sites.
 */
object Ollama {
  private val log = LoggerFactory.getLogger(getClass)

  /** Decide base URL:
   *   1) explicit parameter if provided
   *   2) env OLLAMA_HOST
   *   3) default http://127.0.0.1:11434
   */
  def client(baseOpt: Option[String] = None): OllamaClient = {
    val url = baseOpt
      .orElse(sys.env.get("OLLAMA_HOST"))
      .getOrElse("http://127.0.0.1:11434")
    log.info("Ollama.client: resolved baseUrl={}", url)
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
