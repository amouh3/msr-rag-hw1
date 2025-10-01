package edu.uic.msr.rag
import java.io.File
import edu.uic.msr.pdf.Pdfs

object PdfSmoke {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) { System.err.println("Usage: PdfSmoke <absPathToOnePDF>"); sys.exit(1) }
    val p = args.mkString(" ")
    val txt = Pdfs.readText(p)
    println(s"PDF: $p")
    println(s"length = ${txt.length}")
    println(txt.take(500).replaceAll("\\s+"," ").trim)
  }
}
