package edu.uic.msr

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import scala.util.Try

object Driver {
  def main(args: Array[String]): Unit = {
    // read config path from args (or default to conf/local.conf)
    val confIdx  = args.indexOf("--conf")
    val confPath =
      if (confIdx >= 0 && confIdx + 1 < args.length) args(confIdx + 1)
      else "conf/local.conf"

    val cfg      = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val listFile = cfg.getString("io.pdfListFile")
    val workDir  = cfg.getString("io.workDir")
    Files.createDirectories(Paths.get(workDir))

    // take first 2 PDFs for the smoke test
    val pdfs =
      Source.fromFile(listFile).getLines()
        .map(_.replace("\uFEFF", "").trim)   // strip BOM if present
        .filter(_.nonEmpty)
        .toVector

    println(s"Found ${pdfs.size} PDFs. Extracting first two…")

    // write a tiny CSV artifact with char + chunk counts
    val csvPath = Paths.get(workDir, "chunk_counts.csv")
    Files.writeString(csvPath, "doc,chars,chunks\n", StandardCharsets.UTF_8)

    pdfs.zipWithIndex.foreach { case (p, i) =>
      val res = Try {
        val txt = Pdfs.readText(p)
        val cs  = Chunker.chunks(txt, maxChars = 1000, overlap = 200)
        println(s"PDF[$i]: $p")
        println(s"  text chars = ${txt.length}")
        println(s"  chunks     = ${cs.size}")
        if (cs.nonEmpty)
          println("  sample: " + cs.head.take(200).replace("\n", " ") + "…")
        (p, txt.length, cs.size)
      }.toEither

      res match {
        case Right((doc, chars, chunks)) =>
          val line = s""""$doc",$chars,$chunks\n"""
          Files.writeString(
            csvPath,
            line,
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND
          )
        case Left(e) =>
          println(s"[WARN] failed to process $p: ${e.getMessage}")
      }
    }

    println(s"✅ extract + chunk smoke test complete. CSV: ${csvPath.toString}")
  }
}
