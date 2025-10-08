package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import edu.uic.msr.pdf.Pdfs
import java.nio.file.{Files, Paths}
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import org.slf4j.LoggerFactory

/**
 * PdfsSpec:
 *  - Creates a tiny PDF on disk, calls Pdfs.readText, and checks extracted text.
 *  - Logging is minimal to keep test output clean.
 */
class PdfsSpec extends AnyFunSuite {
  private val log = LoggerFactory.getLogger(getClass)

  test("readText: extracts text from a simple PDF") {
    // create a tiny in-memory PDF
    val doc = new PDDocument()
    val page = new org.apache.pdfbox.pdmodel.PDPage()
    doc.addPage(page)
    val stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)
    val font = org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA
    stream.beginText()
    stream.setFont(font, 12)
    stream.newLineAtOffset(50, 700)
    stream.showText("Hello PDF.")
    stream.endText()
    stream.close()

    val tmp = Files.createTempFile("pdfs-spec-", ".pdf")
    doc.save(tmp.toFile); doc.close()
    log.debug("PdfsSpec: wrote temp PDF at {}", tmp.toAbsolutePath.toString)

    val text = Pdfs.readText(tmp.toAbsolutePath.toString)
    log.debug("PdfsSpec: extracted chars={}", Int.box(text.length))
    assert(text.contains("Hello"))
    Files.deleteIfExists(tmp)
  }
}
