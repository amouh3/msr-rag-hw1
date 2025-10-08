package edu.uic.msr.rag

import java.net.http._
import java.net.URI
import java.time.Duration
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import org.slf4j.LoggerFactory

/**
 * Minimal synchronous HTTP client for a local/remote Ollama server.
 *
 * Endpoints used:
 *  - POST /api/embeddings  { model, prompt } -> { embedding }
 *  - POST /api/generate    { model, prompt, options } -> { response }
 *
 * Logs:
 *  - INFO: base URL resolved, request completion (status, ms)
 *  - DEBUG: payload sizes (chars/dim)
 *  - ERROR: non-2xx responses or JSON parse failures
 *
 * Behavior is unchanged—only logging added.
 */
object OllamaClient {
  private val log = LoggerFactory.getLogger(getClass)

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  private val BASE = sys.env.getOrElse("OLLAMA_HOST", "http://127.0.0.1:11434")
  log.info("OllamaClient: BASE={}", BASE)

  final case class GenerateOptions(num_ctx: Int = 2048, temperature: Double = 0.2)

  /** Call /api/embeddings. Returns the vector. */
  def embed(model: String, text: String): Either[String, Vector[Double]] = {
    val bodyJson = Json.obj(
      "model"  -> Json.fromString(model),
      "prompt" -> Json.fromString(text)
    )
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$BASE/api/embeddings"))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(bodyJson.noSpaces))
      .build()

    val t0 = System.nanoTime()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    val dtMs = (System.nanoTime() - t0) / 1e6
    if (res.statusCode() / 100 != 2) {
      val msg = s"Embeddings HTTP ${res.statusCode()}: ${res.body()}"
      log.error("embed: {}", msg)
      Left(msg)
    } else {
      val parsed = parse(res.body()).flatMap { js =>
        js.hcursor.downField("embedding").as[Vector[Double]]
      }.left.map(_.getMessage)

      parsed match {
        case Right(vec) =>
          log.info("embed: ok (dim={}, ~{} ms, model='{}', textChars={})",
            Int.box(vec.length), Double.box(dtMs), model, Int.box(text.length))
          Right(vec)
        case Left(err) =>
          log.error("embed: JSON parse error: {}", err)
          Left(err)
      }
    }
  }

  /** Call /api/generate with a full prompt string. Returns the model text. */
  def generate(model: String, prompt: String, opts: GenerateOptions = GenerateOptions()): Either[String, String] = {
    val bodyJson = Json.obj(
      "model"   -> Json.fromString(model),
      "prompt"  -> Json.fromString(prompt),
      "stream"  -> Json.fromBoolean(false),
      "options" -> Json.obj(
        "num_ctx"     -> Json.fromInt(opts.num_ctx),
        "temperature" -> Json.fromDoubleOrNull(opts.temperature)
      )
    )

    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$BASE/api/generate"))
      .timeout(Duration.ofSeconds(120))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(bodyJson.noSpaces))
      .build()

    val t0 = System.nanoTime()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    val dtMs = (System.nanoTime() - t0) / 1e6
    if (res.statusCode() / 100 != 2) {
      val msg = s"Generate HTTP ${res.statusCode()}: ${res.body()}"
      log.error("generate: {}", msg)
      Left(msg)
    } else {
      val parsed = parse(res.body()).flatMap(_.hcursor.downField("response").as[String]).left.map(_.getMessage)
      parsed match {
        case Right(txt) =>
          log.info("generate: ok (len={}, ~{} ms, model='{}')", Int.box(txt.length), Double.box(dtMs), model)
          Right(txt)
        case Left(err) =>
          log.error("generate: JSON parse error: {}", err)
          Left(err)
      }
    }
  }
}
