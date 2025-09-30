package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}

object EvalEmbeddings {
  private def cos(a: Array[Float], b: Array[Float]): Float = {
    var s=0.0f; var i=0
    while (i < a.length && i < b.length) { s += a(i)*b(i); i+=1 }
    s
  }
  private def add(a: Array[Float], b: Array[Float]): Array[Float] = a.indices.map(i => a(i)+b(i)).toArray
  private def sub(a: Array[Float], b: Array[Float]): Array[Float] = a.indices.map(i => a(i)-b(i)).toArray

  def main(args: Array[String]): Unit = {
    val confPath = if (args.contains("--conf")) args(args.indexOf("--conf")+1) else "conf/local.conf"
    val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val outDir = Paths.get(cfg.getString("stats.outputDir"))
    Files.createDirectories(outDir)

    // Load token embeddings
    val embFile = outDir.resolve("token_embeddings.csv").toString
    val lines   = Source.fromFile(embFile).getLines().toVector
    val header  = lines.head.split(",").toVector
    val dim     = header.size - 1
    val embs    = lines.tail.flatMap { ln =>
      val cols = ln.split(",")
      if (cols.length == dim+1) {
        val tok  = cols.head
        val vec  = cols.tail.map(_.toFloat).toArray
        Some(tok -> vec)
      } else None
    }.toMap

    // --- Word Similarity (tiny set; adjust to what exists in your vocab) ---
    val similarPairs = Vector(
      "bug" -> "defect",
      "program" -> "software",
      "code" -> "source",
      "repository" -> "repo",
      "issue" -> "bug"
    ).filter { case (a,b) => embs.contains(a) && embs.contains(b) }

    val simCsv = new StringBuilder("w1,w2,cosine\n")
    similarPairs.foreach { case (a,b) =>
      simCsv.append(s"$a,$b,${cos(embs(a), embs(b))}\n")
    }
    Files.write(outDir.resolve("similarity.csv"), simCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // --- Word Analogy: a - b + c ≈ d ---
    val analogies = Vector(
      ("developer","code","bug","fix"),   // developer : code :: bug : fix
      ("issue","bug","patch","commit"),   // issue : bug :: patch : commit
      ("project","repository","paper","conference")
    ).filter { case (a,b,c,_) => embs.contains(a) && embs.contains(b) && embs.contains(c) }

    val anaCsv = new StringBuilder("a,b,c,predicted,cosine\n")
    val toks   = embs.keys.toVector
    val vecs   = toks.map(embs).toArray
    for ((a,b,c,_) <- analogies) {
      val target = add(sub(embs(a), embs(b)), embs(c))
      // nearest neighbor excluding a,b,c
      val best = toks.indices.iterator
        .filter(i => toks(i) != a && toks(i) != b && toks(i) != c)
        .map(i => (toks(i), cos(target, vecs(i))))
        .toArray
        .sortBy{ case (_,s) => -s }
        .headOption
      best.foreach { case (pred,score) =>
        anaCsv.append(s"$a,$b,$c,$pred,$score\n")
      }
    }
    Files.write(outDir.resolve("analogy.csv"), anaCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    println(s"Wrote: ${outDir.resolve("similarity.csv")} and ${outDir.resolve("analogy.csv")}")
  }
}
