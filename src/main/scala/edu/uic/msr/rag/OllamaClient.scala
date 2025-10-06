package edu.uic.msr.rag

import java.net.http._
import java.net.URI
import java.time.Duration
import io.circe._
import io.circe.parser._
import io.circe.syntax._

object OllamaClient {
  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  private val BASE = sys.env.getOrElse("OLLAMA_HOST", "http://127.0.0.1:11434")

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

    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() / 100 != 2) Left(s"Embeddings HTTP ${res.statusCode()}: ${res.body()}")
    else {
      parse(res.body()).flatMap { js =>
        js.hcursor.downField("embedding").as[Vector[Double]]
      }.left.map(_.getMessage)
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

    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() / 100 != 2) Left(s"Generate HTTP ${res.statusCode()}: ${res.body()}")
    else {
      parse(res.body()).flatMap(_.hcursor.downField("response").as[String]).left.map(_.getMessage)
    }
  }
}
