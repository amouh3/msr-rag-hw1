package edu.uic.msr
import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths}
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker

object Driver:
  def main(args: Array[String]): Unit =
    val confIdx  = args.indexOf("--conf")
    val confPath =
      if confIdx >= 0 && confIdx + 1 < args.length then args(confIdx+1)
      else "conf/local.conf"

    val cfg = ConfigFactory.parseFile(java.io.File(confPath)).resolve()
    val listFile = cfg.getString("io.pdfListFile")
    val workDir  = cfg.getString("io.workDir")
    Files.createDirectories(Paths.get(workDir))

    val firstTwo = Source.fromFile(listFile).getLines().take(2).toVector
    println(s"Found ${firstTwo.size} PDFs. Extracting first two?")
    firstTwo.zipWithIndex.foreach { case (p, i) =>
      val txt = Pdfs.readText(p)
      val cs  = Chunker.chunks(txt, maxChars = 1000, overlap = 200)
      println(s"PDF[$i]: $p")
      println(s"  text chars = ${txt.length}")
      println(s"  chunks     = ${cs.size}")
      if cs.nonEmpty then println("  sample: " + cs.head.take(200).replace("\n"," ") + "?")
    }
    println("? extract + chunk smoke test complete.")
