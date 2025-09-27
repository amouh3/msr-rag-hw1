package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import edu.uic.msr.pdf.Pdfs
import java.nio.file.{Files, Paths}
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

class PdfsSpec extends AnyFunSuite {
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

    val text = Pdfs.readText(tmp.toAbsolutePath.toString)
    assert(text.contains("Hello"))
    Files.deleteIfExists(tmp)
  }
}
