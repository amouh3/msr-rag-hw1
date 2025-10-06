package edu.uic.msr.pdf

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FileSystem, Path => HPath}
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

object Pdfs {

  /** Local file path overload (still supported for your Windows runs). */
  def readText(p: Path): String = readText(p.toString)

  /** Universal reader: supports file://, s3a://, hdfs:// URIs. */
  def readText(uri: String): String = {
    def pdfTextFrom(bytes: Array[Byte]): String = {
      val doc = PDDocument.load(bytes)
      try PDFTextStripper().getText(doc) finally doc.close()
    }

    val lower = uri.toLowerCase
    if (lower.startsWith("s3a://") || lower.startsWith("hdfs://")) {
      val conf = new Configuration()
      val fs   = FileSystem.get(new java.net.URI(uri), conf)
      val in: FSDataInputStream = fs.open(new HPath(uri))
      val bytes = try {
        val buf = new Array[Byte](64 * 1024)
        val out = new ByteArrayOutputStream()
        var n = in.read(buf)
        while (n > 0) { out.write(buf, 0, n); n = in.read(buf) }
        out.toByteArray
      } finally in.close()
      pdfTextFrom(bytes)
    } else {
      // treat as local/absolute or relative path
      val bytes = Files.readAllBytes(Path.of(uri))
      pdfTextFrom(bytes)
    }
  }
}
