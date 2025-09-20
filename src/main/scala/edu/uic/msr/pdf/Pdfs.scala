package edu.uic.msr.pdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.{Files, Paths}
import java.io.ByteArrayInputStream
import scala.util.Using

object Pdfs:
  def readText(absPath: String): String =
    val bytes = Files.readAllBytes(Paths.get(absPath))
    Using.resource(PDDocument.load(new ByteArrayInputStream(bytes))){ doc =>
      PDFTextStripper().getText(doc)
    }
