package edu.uic.msr

import com.typesafe.config.{Config, ConfigFactory}
import scala.util.Using
import scala.io.Source
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory

/**
 * EmbedSmoke:
 *  - Loads one PDF path from the configured list, extracts text, chunks, and requests embeddings.
 *  - Prints a concise human-readable line and logs timings & dims.
 *
 * Logging:
 *  - INFO: config snapshot, first path, chunk count, embedding summary
 *  - WARN: empty text extraction
 *  - ERROR: early abort conditions
 */
object EmbedSmoke {
  private val log = LoggerFactory.getLogger(getClass)

  private def loadConfig(args: Array[String]): Config = {
    val base = ConfigFactory.load() // application.conf on classpath
    val fromArg =
      args.sliding(2).find(a => a.length == 2 && a(0) == "--conf").map(_(1))
    fromArg
      .map(p => ConfigFactory.parseFile(new java.io.File(p)).resolve().withFallback(base))
      .getOrElse(base)
  }

  def main(args: Array[String]): Unit = {
    val cfg      = loadConfig(args)
    val list     = cfg.getString("io.pdfListFile")
    val model    = cfg.getString("embed.model")
    val maxChars = if (cfg.hasPath("embed.maxChars")) cfg.getInt("embed.maxChars") else 800
    val overlap  = if (cfg.hasPath("embed.overlap"))  cfg.getInt("embed.overlap")  else 160

    log.info(s"EmbedSmoke: list=$list model=$model maxChars=$maxChars overlap=$overlap")
    sys.env.get("OLLAMA_HOST").foreach(h => log.info(s"OLLAMA_HOST=$h"))

    val firstPathOpt =
      Using.resource(Source.fromFile(list)) { src =>
        src.getLines().map(_.replace("\uFEFF","").trim).find(_.nonEmpty)
      }

    if (firstPathOpt.isEmpty) {
      log.error("No PDF paths found in io.pdfListFile")
      sys.exit(1)
    }

    val first = firstPathOpt.get
    log.info(s"Reading first PDF: $first")

    val t0   = System.nanoTime()
    val text = Pdfs.readText(first)
    if (text.trim.isEmpty) {
      log.warn("Extracted text is empty; PDF might be image-only or corrupted.")
    }

    val chunks = Chunker.chunks(text, maxChars = maxChars, overlap = overlap).take(4)
    log.info(s"Prepared ${chunks.size} chunk(s) for embedding")

    if (chunks.isEmpty) {
      log.error("No chunks to embed; aborting.")
      sys.exit(2)
    }

    try {
      val t1   = System.nanoTime()
      val vecs = Ollama.embed(chunks.toVector, model)
      val t2   = System.nanoTime()

      val dim  = vecs.headOption.map(_.length).getOrElse(0)
      log.info(f"Embedded ${vecs.size} chunk(s); dim=$dim; time ${(t2 - t1)/1e3/1e3}%.1f ms")

      // Extra guard to prove non-empty embeddings:
      if (vecs.isEmpty || dim == 0) {
        log.error("Embeddings came back empty (size=0 or dim=0).")
        sys.exit(3)
      }

      // Quick peek at the first 5 numbers of the first vector (TRACE-level):
      val preview = vecs.head.take(5).mkString(", ")
      log.trace(s"vec[0][0..4]= $preview")

      // Also print a simple line for human eyeballs in CI logs:
      println(s"OK: vectors=${vecs.size} dim=$dim first5=[$preview]")

      val ms = (System.nanoTime() - t0) / 1e6
      log.info(f"EmbedSmoke done in $ms%.1f ms")
    } catch {
      case e: Throwable =>
        log.error("Embedding call failed", e)
        sys.exit(4)
    }
  }
}
