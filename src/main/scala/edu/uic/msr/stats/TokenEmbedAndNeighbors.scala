package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}
import edu.uic.msr.ollama.Ollama

object TokenEmbedAndNeighbors {
  private def l2(v: Array[Float]): Array[Float] = {
    val n = math.sqrt(v.foldLeft(0.0){ (acc,x) => acc + x*x }).toFloat
    if (n == 0f) v else v.map(_ / n)
  }
  private def cos(a: Array[Float], b: Array[Float]): Float = {
    var s = 0.0f; var i = 0
    while (i < a.length && i < b.length) { s += a(i) * b(i); i += 1 }
    s
  }

  def main(args: Array[String]): Unit = {
    val confPath = if (args.contains("--conf")) args(args.indexOf("--conf")+1) else "conf/local.conf"
    val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()

    val outDir   = Paths.get(cfg.getString("stats.outputDir"))
    val model    = cfg.getString("embed.model")
    val topN     = if (cfg.hasPath("stats.topVocab")) cfg.getInt("stats.topVocab") else 5000
    val kNN      = if (cfg.hasPath("stats.kNN")) cfg.getInt("stats.kNN") else 10
    Files.createDirectories(outDir)

    // Read topN tokens from vocab.csv
    val vocabCsv = outDir.resolve("vocab.csv").toString
    val vocab = Source.fromFile(vocabCsv).getLines().drop(1).take(topN).map { line =>
      val Array(tok, id, freq) = line.split(",", 3)
      tok
    }.toVector

    // Embed (batch in Ollama implementation)
    val vecs = Ollama.embed(vocab, model).map(l2)
    val dim  = vecs.headOption.map(_.length).getOrElse(0)
    println(s"Embedded ${vecs.size} tokens; dim=$dim")

    // Write token_embeddings.csv
    val embCsv = new StringBuilder
    embCsv.append("token")
    for (d <- 0 until dim) embCsv.append(s",d$d")
    embCsv.append("\n")
    vocab.zip(vecs).foreach { case (t, v) =>
      embCsv.append(t)
      v.foreach(x => embCsv.append(f",$x"))
      embCsv.append("\n")
    }
    Files.write(outDir.resolve("token_embeddings.csv"), embCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // Compute neighbors
    val neighbors = new StringBuilder
    neighbors.append("token,neighbor,cosine\n")
    val arr = vecs.toArray
    val idx = vocab.toArray
    for (i <- arr.indices) {
      // brute-force top-k
      val scores = arr.indices.iterator
        .filter(_ != i)
        .map(j => (j, cos(arr(i), arr(j))))
        .toArray
        .sortBy{ case (_,s) => -s }
        .take(kNN)
      scores.foreach { case (j,s) =>
        neighbors.append(s"${idx(i)},${idx(j)},$s\n")
      }
    }
    Files.write(outDir.resolve("neighbors.csv"), neighbors.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    println(s"Wrote: ${outDir.resolve("token_embeddings.csv")} and ${outDir.resolve("neighbors.csv")}")
  }
}
