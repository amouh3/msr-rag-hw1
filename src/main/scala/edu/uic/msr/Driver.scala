package edu.uic.msr

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import scala.util.Try
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.slf4j.LoggerFactory

/**
 * Driver:
 *  - Loads config, optionally generates tiny demo PDFs, reads list of PDF paths,
 *    extracts text, chunks, prints a small report, and writes a CSV summary.
 *
 * Logging:
 *  - INFO: config paths, demo creation, counts, final CSV path
 *  - WARN: per-file extraction failures
 *  - DEBUG: chunk/text sizes per doc (kept to printlns for your original UX)
 */
object Driver {

  private val log = LoggerFactory.getLogger(getClass)

  /** Create a tiny single-page PDF containing the provided text. */
  private def makeTinyPdf(path: java.nio.file.Path, text: String): Unit = {
    val doc = new PDDocument()
    val page = new PDPage()
    doc.addPage(page)
    val stream = new PDPageContentStream(doc, page)
    stream.beginText()
    stream.setFont(PDType1Font.HELVETICA, 12)
    stream.newLineAtOffset(50, 700)
    stream.showText(text)
    stream.endText()
    stream.close()
    Files.createDirectories(path.getParent)
    doc.save(path.toFile)
    doc.close()
  }

  def main(args: Array[String]): Unit = {
    // args: --conf conf/local.conf [--demo]
    val confIdx  = args.indexOf("--conf")
    val confPath =
      if (confIdx >= 0 && confIdx + 1 < args.length) args(confIdx + 1)
      else "conf/local.conf"

    val demoMode = args.contains("--demo")

    log.info("Driver: conf='{}' demoMode={}", confPath, Boolean.box(demoMode))

    val cfg      = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val listFile = cfg.getString("io.pdfListFile")
    val workDir  = cfg.getString("io.workDir")
    Files.createDirectories(Paths.get(workDir))

    // If --demo, create 2 tiny PDFs and a temp list.txt that points to them
    val effectiveListFile =
      if (demoMode) {
        val demoRoot = Paths.get("samples", "generated")
        val p1 = demoRoot.resolve("demo1.pdf")
        val p2 = demoRoot.resolve("demo2.pdf")
        makeTinyPdf(p1, "Hello PDF. This is a tiny demo document number one.")
        makeTinyPdf(p2, "Hello again. This is demo document number two for chunking.")
        val lst = demoRoot.resolve("list.txt")
        val content = s"${p1.toAbsolutePath.toString}\n${p2.toAbsolutePath.toString}\n"
        Files.write(lst, content.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        println(s"[Driver] --demo mode: created ${p1} and ${p2}")
        log.info("Driver: demo artifacts created at {}", demoRoot.toAbsolutePath.toString)
        lst.toString
      } else {
        listFile
      }

    // Load paths (if any)
    val pdfs =
      if (Files.exists(Paths.get(effectiveListFile))) {
        val lines = Source.fromFile(effectiveListFile).getLines()
          .map(_.replace("\uFEFF", "").trim)
          .filter(_.nonEmpty)
          .toVector
        if (lines.isEmpty) {
          Console.err.println(
            s"[Driver] No paths found in $effectiveListFile.\n" +
              s"  • Provide absolute PDF paths, one per line\n" +
              s"  • Or run: sbt \"runMain edu.uic.msr.Driver --conf $confPath --demo\""
          )
          sys.exit(2)
        }
        lines
      } else {
        Console.err.println(
          s"[Driver] Missing file: $effectiveListFile\n" +
            s"  • Create it and list absolute PDF paths, one per line\n" +
            s"  • Or run: sbt \"runMain edu.uic.msr.Driver --conf $confPath --demo\""
        )
        sys.exit(1)
      }

    log.info("Driver: found {} PDF path(s)", Int.box(pdfs.size))
    println(s"Found ${pdfs.size} PDFs. Extracting…")

    // write a tiny CSV artifact with char + chunk counts
    val csvPath = Paths.get(workDir, "chunk_counts.csv")
    Files.write(csvPath, "doc,chars,chunks,sample\n".getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    pdfs.zipWithIndex.foreach { case (p, i) =>
      val res = Try {
        val txt = Pdfs.readText(p)
        val cs  = Chunker.chunks(txt, maxChars = 1000, overlap = 200)
        println(s"PDF[$i]: $p")
        println(s"  text chars = ${txt.length}")
        println(s"  chunks     = ${cs.size}")
        val sample = cs.headOption.getOrElse("").take(200).replace("\n"," ")
        if (sample.nonEmpty) println(s"  sample     = $sample…")
        (p, txt.length, cs.size, sample)
      }.toEither

      res match {
        case Right((doc, chars, chunks, sample)) =>
          val line = s""""$doc",$chars,$chunks,"${sample.replace("\"","'")}"\n"""
          Files.write(csvPath, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
        case Left(e) =>
          log.warn("Driver: failed to process {}: {}", p, e.getMessage)
          println(s"[WARN] failed to process $p: ${e.getMessage}")
      }
    }

    log.info("Driver: CSV written → {}", csvPath.toAbsolutePath.toString)
    println(s"✅ extract + chunk smoke test complete → ${csvPath.toAbsolutePath}")
    if (demoMode) println("[Driver] Tip: now run your real config without --demo against your corpus.")
  }
}
