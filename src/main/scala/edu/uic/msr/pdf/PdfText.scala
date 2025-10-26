package edu.uic.msr.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.{ByteArrayInputStream, InputStream}

object PdfText {
  /** Extract plain text from PDF bytes using PDFBox. Closes all resources. */
  def extract(bytes: Array[Byte]): String = {
    var is: InputStream = null
    var doc: PDDocument = null
    try {
      is = new ByteArrayInputStream(bytes)
      doc = PDDocument.load(is)
      val stripper = new PDFTextStripper()
      stripper.getText(doc)
    } finally {
      if (doc != null) doc.close()
      if (is != null) is.close()
    }
  }

  /** Heuristic: treat files ending with .pdf (case-insensitive) as PDF. */
  def isPdf(path: String): Boolean =
    path.toLowerCase.endsWith(".pdf")
}
