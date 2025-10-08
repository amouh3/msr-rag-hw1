package edu.uic.msr.rag
import java.io.File
import edu.uic.msr.pdf.Pdfs
import org.slf4j.LoggerFactory

/**
 * PdfSmoke:
 *  Simple smoke test to confirm PDF extraction works.
 *  Inputs: absolute path to one PDF (first arg).
 *  Output: prints file path, extracted length, and a short preview.
 *
 * Notes:
 *  - Behavior unchanged; println outputs preserved for quick CLI use.
 *  - Logging added for start/end and basic diagnostics.
 */
object PdfSmoke {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) { System.err.println("Usage: PdfSmoke <absPathToOnePDF>"); sys.exit(1) }
    val p = args.mkString(" ")
    log.info("PdfSmoke: reading '{}'", p)

    val txt = Pdfs.readText(p)
    log.debug("PdfSmoke: extracted chars={}", Int.box(txt.length))

    println(s"PDF: $p")
    println(s"length = ${txt.length}")
    println(txt.take(500).replaceAll("\\s+"," ").trim)

    log.info("PdfSmoke: done")
  }
}
