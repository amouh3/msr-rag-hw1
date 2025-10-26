package edu.uic.msr

import com.typesafe.config.{Config, ConfigFactory}
import scala.io.Source
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory

/**
 * IndexAll:
 *  - Loads a list of PDF paths from config (io.pdfListFile)
 *  - Extracts text, chunks it, embeds chunks (batched), and writes a CSV with vectors
 *
 * Logging:
 *  - INFO: job summary, per-PDF progress, final path
 *  - DEBUG: workDir/maxDocs and batch details
 */
object IndexAll {
  // --- logging ---
  private val log = LoggerFactory.getLogger(getClass)

  // --- arg helpers ---
  private def argAfter(flag: String, args: Array[String]): Option[String] =
    args.sliding(2).find(a => a.length == 2 && a(0) == flag).map(_(1))

  // --- config loader (no extra file needed) ---
  private def loadConfig(confPathOpt: Option[String]): Config = {
    val base = ConfigFactory.load() // loads application.conf / reference.conf on classpath
    confPathOpt
      .map(p => ConfigFactory.parseFile(new java.io.File(p)).resolve().withFallback(base))
      .getOrElse(base)
  }

  def main(args: Array[String]): Unit = {
    val cfg = loadConfig(argAfter("--conf", args))

    val list    = cfg.getString("io.pdfListFile")
    val workDir = cfg.getString("io.workDir")
    val outCsv  = cfg.getString("index.out")
    val model   = cfg.getString("embed.model")
    val batch   = if (cfg.hasPath("embed.batch")) cfg.getInt("embed.batch") else 8
    val maxDocs = if (cfg.hasPath("index.maxDocs")) cfg.getInt("index.maxDocs") else Int.MaxValue

    Files.createDirectories(Paths.get(workDir))

    val docs =
      Source.fromFile(list).getLines()
        .map(_.replace("\uFEFF", "").trim)
        .filter(_.nonEmpty)
        .take(maxDocs)
        .toVector

    log.info(s"Indexing ${docs.size} PDFs -> $outCsv using model='$model' batch=$batch")
    log.debug(s"workDir=$workDir, maxDocs=$maxDocs")

    val writer = java.nio.file.Files.newBufferedWriter(Paths.get(outCsv), StandardCharsets.UTF_8)
    try {
      writer.write("doc,chunk_idx,chars,vec\n")

      docs.zipWithIndex.foreach { case (doc, di) =>
        val t0     = System.nanoTime()
        val text   = Pdfs.readText(doc)
        val chunks = Chunker.chunks(text, maxChars = 800, overlap = 160)

        log.info(f"PDF[$di%3d] chunks=${chunks.size}%4d  file=$doc")

        // Embed in mini-batches
        chunks.grouped(batch).zipWithIndex.foreach { case (group, gi) =>
          val vecs: Vector[Array[Float]] = Ollama.embed(group.toVector, model)
          group.zip(vecs).zipWithIndex.foreach { case ((chunkText, vec), cj) =>
            val idxInDoc = gi * batch + cj
            val vecStr   = vec.mkString(" ")
            writer.write(s""""$doc",$idxInDoc,${chunkText.length},"$vecStr"\n""")
          }
        }

        val ms = (System.nanoTime() - t0) / 1e6
        log.info(f"  done in $ms%.1f ms")
      }

      log.info(s"✅ index written to $outCsv")
    } finally {
      writer.close()
    }
  }
}
