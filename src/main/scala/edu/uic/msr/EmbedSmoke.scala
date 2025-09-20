package edu.uic.msr
import com.typesafe.config.ConfigFactory
import scala.io.Source
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama

object EmbedSmoke {
  def main(args: Array[String]): Unit = {
    val confPath = if (args.length >= 2 && args(0) == "--conf") args(1) else "conf/local.conf"
    val cfg   = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val list  = cfg.getString("io.pdfListFile")
    val model = cfg.getString("embed.model")

    val first  = Source.fromFile(list).getLines().map(_.replace("\uFEFF","").trim).filter(_.nonEmpty).next()
    val txt    = Pdfs.readText(first)
    val chunks = Chunker.chunks(txt, 800, 160).take(4) // just 4 chunks

    // 👇 call the Ollama singleton directly
    val vecs = Ollama.embed(chunks, model)
    println(s"Embedded ${vecs.size} chunks; dim = ${vecs.headOption.map(_.length).getOrElse(0)}")
  }
}
