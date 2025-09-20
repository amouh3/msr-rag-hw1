package edu.uic.msr

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama   // object, not class

object IndexAll {
  def main(args: Array[String]): Unit = {
    val confPath =
      if (args.length >= 2 && args(0) == "--conf") args(1) else "conf/local.conf"

    val cfg     = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val list    = cfg.getString("io.pdfListFile")
    val workDir = cfg.getString("io.workDir")
    val outCsv  = cfg.getString("index.out")
    val model   = cfg.getString("embed.model")
    val batch   = if (cfg.hasPath("embed.batch")) cfg.getInt("embed.batch") else 8
    val maxDocs = if (cfg.hasPath("index.maxDocs")) cfg.getInt("index.maxDocs") else Int.MaxValue

    Files.createDirectories(Paths.get(workDir))

    val docs =
      Source.fromFile(list).getLines().map(_.replace("\uFEFF","").trim).filter(_.nonEmpty).take(maxDocs).toVector

    println(s"Indexing ${docs.size} PDFs -> $outCsv using model '$model' (batch=$batch)")

    val writer = java.nio.file.Files.newBufferedWriter(Paths.get(outCsv), StandardCharsets.UTF_8)
    try {
      writer.write("doc,chunk_idx,chars,vec\n")

      docs.zipWithIndex.foreach { case (doc, di) =>
        val t0 = System.nanoTime()
        val text   = Pdfs.readText(doc)
        val chunks = Chunker.chunks(text, maxChars = 800, overlap = 160)

        println(f"PDF[$di%d]: $doc  chunks=${chunks.size}%d")

        // Embed in mini-batches using the object Ollama
        chunks.grouped(batch).zipWithIndex.foreach { case (group, gi) =>
          val vecs: Vector[Array[Float]] = Ollama.embed(group.toVector, model)
          group.zip(vecs).zipWithIndex.foreach { case ((chunkText, vec), cj) =>
            val idxInDoc = gi * batch + cj
            val vecStr   = vec.mkString(" ")
            writer.write(s""""$doc",$idxInDoc,${chunkText.length},"$vecStr"\n""")
          }
        }

        val ms = (System.nanoTime() - t0) / 1e6
        println(f"  done in $ms%.1f ms")
      }

      println(s"✅ index written to $outCsv")
    } finally {
      writer.close()
    }
  }
}
