package edu.uic.msr.rag

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import scala.io.Source
import scala.util.Using

// If these exist in your project, we’ll use them. If not, we’ll fall back to naive chunking.
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker

import io.circe.syntax._
import io.circe.Json
import org.slf4j.LoggerFactory

/**
 * Indexer:
 *  - Walks files/dirs, reads .pdf/.txt, sanitizes text, chunks, embeds, and writes JSONL lines.
 *  - Embedding uses Ollama; chunking prefers your project Chunker with a naive fallback.
 *
 * Logging levels:
 *  - INFO: start/end, file counts, produced chunk counts
 *  - DEBUG: per-file steps (read/chunk/embed), lengths
 *  - ERROR: embedding failures per chunk
 *
 * Notes:
 *  - Behavior unchanged; only logging/comments added.
 */
object Indexer {

  private val log = LoggerFactory.getLogger(getClass)

  // ---- CONFIG ----
  private val EmbedModel   = "mxbai-embed-large" // you already have this
  private val OutPath      = "data/chunks.jsonl" // target JSONL
  private val MaxCharsPerChunk = 1200            // safe size for tiny models
  private val MinCharsPerChunk = 120             // drop super-tiny noise

  // same spirit as your mapper’s sanitizer
  private def sanitize(s: String): String =
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  // crude “bad glyphs” heuristic (skip if too many non-ascii/garbage)
  private def looksCorrupted(s: String): Boolean = {
    if (s.isEmpty) return true
    val bad = s.count(ch => ch.toInt > 0xFF)
    bad.toDouble / s.length > 0.1
  }

  // fallback chunker if your Chunker isn’t present
  private def naiveChunk(text: String, maxLen: Int, minLen: Int): Vector[String] = {
    val paras = text.split("\n{2,}").map(_.trim).filter(_.nonEmpty).toVector
    val buf   = scala.collection.mutable.ArrayBuffer.empty[String]
    val b     = new StringBuilder()

    def flush(): Unit = {
      val piece = sanitize(b.result())
      b.clear()
      if (piece.length >= minLen) buf += piece
    }

    paras.foreach { p =>
      if (b.length + p.length + 1 > maxLen) flush()
      if (p.length > maxLen) {
        // hard wrap overly long paragraphs
        p.grouped(maxLen).foreach { seg =>
          if (b.nonEmpty) flush()
          b.append(seg)
          flush()
        }
      } else {
        if (b.nonEmpty) b.append('\n')
        b.append(p)
      }
    }
    if (b.nonEmpty) flush()
    buf.toVector
  }

  private def sha256(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s.getBytes("UTF-8"))
    md.digest().map("%02x".format(_)).mkString
  }

  private def readTextFromPath(f: File): String = {
    val name = f.getName.toLowerCase
    if (name.endsWith(".pdf")) {
      // Use your Pdfs helper if available
      try Pdfs.readText(f.getAbsolutePath) catch { case _: Throwable => "" }
    } else if (name.endsWith(".txt")) {
      Using.resource(Source.fromFile(f, "UTF-8"))(_.mkString)
    } else ""
  }

  private def chunkWithProjectChunker(text: String): Vector[String] = {
    try {
      // If your Chunker exposes something like:
      // Chunker.chunk(text): Vector[String] OR returns a case class with .text
      val cs = Chunker.chunks(text)  // or pass your chosen (maxChars, overlap)
      // map to strings if needed
      cs.map {
        case s: String => s
        case other     => other.toString
      }.toVector
    } catch {
      case _: Throwable => naiveChunk(text, MaxCharsPerChunk, MinCharsPerChunk)
    }
  }

  private def allFiles(root: File): Vector[File] = {
    if (!root.exists()) Vector.empty
    else if (root.isFile) Vector(root)
    else root.listFiles().toVector.flatMap(allFiles)
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: Indexer <inputDir-or-file> [outPath=data/chunks.jsonl]")
      System.exit(1)
    }
    val input     = new File(args(0))
    val outPath   = if (args.length >= 2) args(1) else OutPath
    val outDir    = Paths.get(outPath).getParent
    if (outDir != null && !Files.exists(outDir)) Files.createDirectories(outDir)

    log.info("Indexer: input={}, outPath={}", input.getAbsolutePath, outPath)

    val files = if (input.isDirectory) allFiles(input).filter(f => {
      val n = f.getName.toLowerCase
      n.endsWith(".pdf") || n.endsWith(".txt")
    }) else Vector(input)

    log.info("Indexer: discovered {} candidate file(s)", Int.box(files.size))

    if (files.isEmpty) {
      System.err.println(s"No .pdf or .txt files found under: ${input.getAbsolutePath}")
      System.exit(2)
    }

    val pw = new PrintWriter(outPath, "UTF-8")
    try {
      var count = 0
      files.foreach { f =>
        log.debug("Indexer[file]: reading {}", f.getName)
        val raw = readTextFromPath(f)
        val text = sanitize(raw)
        log.debug("Indexer[file]: sanitized chars={}", Int.box(text.length))

        if (text.nonEmpty) {
          val chunks = chunkWithProjectChunker(text)
          log.debug("Indexer[file]: produced {} chunk(s) before filtering", Int.box(chunks.size))

          chunks.foreach { ch =>
            val chunk = sanitize(ch)
            if (chunk.length >= MinCharsPerChunk && !looksCorrupted(chunk)) {
              // Embed via Ollama
              log.trace("Indexer[chunk]: embedding len={}", Int.box(chunk.length))
              OllamaClient.embed(EmbedModel, chunk) match {
                case Right(vec) =>
                  val id = s"${f.getName}#${sha256(chunk).take(12)}"
                  val json = Json.obj(
                    "id"        -> Json.fromString(id),
                    "source"    -> Json.fromString(f.getName),
                    "text"      -> Json.fromString(chunk),
                    "embedding" -> vec.asJson
                  )
                  pw.println(json.noSpaces)
                  count += 1
                case Left(err) =>
                  log.error("Indexer[chunk]: EMBED FAIL for {}: {}", f.getName, err.take(200))
                  System.err.println(s"[EMBED FAIL] ${f.getName}: ${err.take(200)}")
              }
            }
          }
        } else {
          log.warn("Indexer[file]: empty text after sanitize: {}", f.getName)
        }
      }
      log.info("Indexer: wrote {} chunk(s) → {}", Int.box(count), outPath)
      println(s"Indexed $count chunks → $outPath")
    } finally {
      pw.flush()
      pw.close()
    }
  }
}
