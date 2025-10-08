package edu.uic.msr.pdf

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FileSystem, Path => HPath}
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory

/**
 * PDF utilities:
 *  - `readText(Path)` overload for local file paths.
 *  - `readText(String)` for universal URIs: supports file://, s3a://, hdfs://.
 *
 * Behavior notes:
 *  - Uses PDFBox to extract text.
 *  - For remote filesystems (s3a/hdfs), streams bytes into memory, then extracts.
 *  - Closes all streams/documents (try/finally).
 *  - Does not mutate global Hadoop Configuration.
 *
 * Logging:
 *  - INFO: entry, chosen scheme, document size (bytes) when known, completion
 *  - DEBUG: read durations, byte counts
 *  - ERROR: extraction/IO failures
 */
object Pdfs {

  private val log = LoggerFactory.getLogger(getClass)

  /** Local file path overload (still supported for your Windows runs). */
  def readText(p: Path): String = readText(p.toString)

  /** Universal reader: supports file://, s3a://, hdfs:// URIs. */
  def readText(uri: String): String = {
    log.info("Pdfs.readText: uri={}", uri)
    def pdfTextFrom(bytes: Array[Byte]): String = {
      log.debug("Pdfs.readText: extracting text via PDFBox, bytes={}", Int.box(bytes.length))
      val t0 = System.nanoTime()
      val doc = PDDocument.load(bytes)
      try {
        val text = PDFTextStripper().getText(doc)
        val dtMs = (System.nanoTime() - t0) / 1e6
        log.debug("Pdfs.readText: PDFBox extraction completed (~{} ms, chars={})", Double.box(dtMs), Int.box(text.length))
        text
      } catch {
        case ex: Exception =>
          log.error("Pdfs.readText: PDFBox extraction failed: {}", ex.getMessage)
          throw ex
      } finally {
        doc.close()
      }
    }

    val lower = uri.toLowerCase
    if (lower.startsWith("s3a://") || lower.startsWith("hdfs://")) {
      log.info("Pdfs.readText: remote filesystem detected (s3a/hdfs)")
      val t0 = System.nanoTime()
      val conf = new Configuration()
      val fs   = FileSystem.get(new java.net.URI(uri), conf)
      val in: FSDataInputStream = fs.open(new HPath(uri))
      val bytes = try {
        val buf = new Array[Byte](64 * 1024)
        val out = new ByteArrayOutputStream()
        var n = in.read(buf)
        var total = 0L
        while (n > 0) { out.write(buf, 0, n); total += n; n = in.read(buf) }
        log.debug("Pdfs.readText: streamed remote bytes={}", Long.box(total))
        out.toByteArray
      } finally in.close()
      val dtMs = (System.nanoTime() - t0) / 1e6
      log.debug("Pdfs.readText: remote read completed (~{} ms, bytes={})", Double.box(dtMs), Int.box(bytes.length))
      pdfTextFrom(bytes)
    } else {
      // treat as local/absolute or relative path
      log.info("Pdfs.readText: local filesystem path")
      val t0 = System.nanoTime()
      val bytes = Files.readAllBytes(Path.of(uri))
      val dtMs = (System.nanoTime() - t0) / 1e6
      log.debug("Pdfs.readText: local read completed (~{} ms, bytes={})", Double.box(dtMs), Int.box(bytes.length))
      pdfTextFrom(bytes)
    }
  }
}
