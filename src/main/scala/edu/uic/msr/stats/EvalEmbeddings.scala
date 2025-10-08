package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}
import org.slf4j.LoggerFactory

/**
 * EvalEmbeddings:
 *  - Loads token embeddings from CSV (token,d0..d{dim-1})
 *  - Computes:
 *      1) Similarity pairs (writes similarity.csv)
 *      2) Simple analogies a - b + c (writes analogy.csv)
 *
 * Logging:
 *  - INFO: config paths, dimension, counts, output files
 *  - DEBUG: sizes of candidate lists
 *  - No behavior changes; still skips rows with wrong column counts.
 */
object EvalEmbeddings {
  private val log = LoggerFactory.getLogger(getClass)

  private def dot(a: Array[Float], b: Array[Float]): Float = {
    var s=0.0f; var i=0
    val n = math.min(a.length, b.length)
    while (i < n) { s += a(i)*b(i); i+=1 }
    s
  }
  private def add(a: Array[Float], b: Array[Float]): Array[Float] =
    a.indices.map(i => a(i)+b(i)).toArray
  private def sub(a: Array[Float], b: Array[Float]): Array[Float] =
    a.indices.map(i => a(i)-b(i)).toArray
  private def l2(v: Array[Float]): Array[Float] = {
    val n = math.sqrt(v.foldLeft(0.0){(acc,x)=>acc+x*x}).toFloat
    if (n==0f) v else v.map(_/n)
  }

  def main(args: Array[String]): Unit = {
    val confPath = if (args.contains("--conf")) args(args.indexOf("--conf")+1) else "conf/local.conf"
    val cfg      = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
    val outDir   = Paths.get(cfg.getString("stats.outputDir"))
    Files.createDirectories(outDir)

    log.info("EvalEmbeddings: conf='{}', outDir='{}'", confPath, outDir.toString)

    // Load token embeddings (header: token,d0..d{dim-1})
    val embFile = outDir.resolve("token_embeddings.csv").toString
    val src     = Source.fromFile(embFile)
    val lines   = try src.getLines().toVector finally src.close()
    val header  = lines.head.split(",").toVector
    val dim     = header.size - 1
    log.info("Embeddings: file='{}', tokens={}, dim={}", embFile, Int.box(lines.size - 1), Int.box(dim))

    val embs = lines.tail.flatMap { ln =>
      val cols = ln.split(",")
      if (cols.length == dim + 1) {
        val tok  = cols.head
        val vec  = cols.tail.map(_.toFloat).toArray
        Some(tok -> vec) // assumed already L2 from previous step
      } else None
    }.toMap
    log.info("Loaded embeddings: {}", Int.box(embs.size))

    // ---- Word Similarity ----
    val similarPairs = Vector(
      "bug" -> "defect",
      "program" -> "software",
      "code" -> "source",
      "repository" -> "repo",
      "issue" -> "bug"
    ).filter { case (a,b) => embs.contains(a) && embs.contains(b) }
    log.debug("Similarity pairs kept: {}", Int.box(similarPairs.size))

    val simCsv = new StringBuilder("w1,w2,cosine\n")
    similarPairs.foreach { case (a,b) =>
      simCsv.append(s"$a,$b,${dot(embs(a), embs(b))}\n")
    }
    Files.write(outDir.resolve("similarity.csv"), simCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // ---- Word Analogy: a - b + c ≈ d (normalize target) ----
    val analogies = Vector(
      ("developer","code","bug","fix"),
      ("issue","bug","patch","commit"),
      ("project","repository","paper","conference")
    ).filter { case (a,b,c,_) => embs.contains(a) && embs.contains(b) && embs.contains(c) }
    log.debug("Analogy triplets kept: {}", Int.box(analogies.size))

    val anaCsv = new StringBuilder("a,b,c,predicted,cosine\n")
    val toks   = embs.keys.toVector
    val vecs   = toks.map(embs).toArray

    for ((a,b,c,_) <- analogies) {
      val target = l2(add(sub(embs(a), embs(b)), embs(c)))  // normalize
      val best = toks.indices.iterator
        .filter(i => toks(i) != a && toks(i) != b && toks(i) != c)
        .map(i => (toks(i), dot(target, vecs(i))))
        .toArray
        .sortBy{ case (_,s) => -s }
        .headOption
      best.foreach { case (pred,score) =>
        anaCsv.append(s"$a,$b,$c,$pred,$score\n")
      }
    }
    Files.write(outDir.resolve("analogy.csv"), anaCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    log.info("EvalEmbeddings: wrote {}, {}",
      outDir.resolve("similarity.csv").toString,
      outDir.resolve("analogy.csv").toString)

    println(s"Wrote: ${outDir.resolve("similarity.csv")} and ${outDir.resolve("analogy.csv")}")
  }
}
