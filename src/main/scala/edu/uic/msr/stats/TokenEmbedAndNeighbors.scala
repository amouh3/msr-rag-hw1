package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory

/**
 * TokenEmbedAndNeighbors:
 *  - Reads the top-N tokens from stats.outputDir/vocab.csv
 *  - Gets embeddings from Ollama in batches and L2-normalizes them
 *  - Writes token_embeddings.csv and brute-force kNN neighbors.csv
 *
 * Logging:
 *  - INFO: config snapshot, counts, output paths
 *  - DEBUG: batch sizes, dims
 */
object TokenEmbedAndNeighbors {
  private val log = LoggerFactory.getLogger(getClass)

  private def l2(v: Array[Float]): Array[Float] = {
    val n = math.sqrt(v.foldLeft(0.0){ (acc,x) => acc + x*x }).toFloat
    if (n == 0f) v else v.map(_ / n)
  }
  private def cos(a: Array[Float], b: Array[Float]): Float = {
    var s = 0.0f; var i = 0
    val len = math.min(a.length, b.length)
    while (i < len) { s += a(i) * b(i); i += 1 }
    s
  }

  def main(args: Array[String]): Unit = {
    val confPath = if (args.contains("--conf")) args(args.indexOf("--conf")+1) else "conf/local.conf"
    val cfg      = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()

    val outDir   = Paths.get(cfg.getString("stats.outputDir"))
    val model    = cfg.getString("embed.model")
    val topN     = if (cfg.hasPath("stats.topVocab")) cfg.getInt("stats.topVocab") else 5000
    val kNN      = if (cfg.hasPath("stats.kNN"))       cfg.getInt("stats.kNN")       else 10
    val batch    = if (cfg.hasPath("embed.batch"))     cfg.getInt("embed.batch")     else 64
    Files.createDirectories(outDir)

    log.info("TokenEmbedAndNeighbors: conf='{}', outDir='{}', model='{}', topN={}, kNN={}, batch={}",
      confPath, outDir.toString, model, Int.box(topN), Int.box(kNN), Int.box(batch))

    // Read topN tokens from vocab.csv (expects header: token,token_id,freq)
    val vocabCsv = outDir.resolve("vocab.csv").toString
    val vocab = Source.fromFile(vocabCsv).getLines().drop(1).take(topN).flatMap { line =>
      val cols = line.split(",", 3)
      if (cols.length >= 1) Some(cols(0)) else None
    }.toVector
    log.info("Loaded {} tokens from {}", Int.box(vocab.size), vocabCsv)

    // ---- Batched embedding + L2 normalization ----
    val vecs = vocab
      .grouped(batch)
      .flatMap { grp =>
        log.debug("Embedding batch size={}", Int.box(grp.size))
        Ollama.embed(grp.toVector, model)
      }
      .toVector
      .map(l2)

    val dim  = vecs.headOption.map(_.length).getOrElse(0)
    println(s"Embedded ${vecs.size} tokens (requested topN=$topN); dim=$dim; batch=$batch")
    log.info("Embeddings ready: count={}, dim={}", Int.box(vecs.size), Int.box(dim))

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

    // Compute neighbors (brute-force)
    val neighbors = new StringBuilder
    neighbors.append("token,neighbor,cosine\n")
    val arr = vecs.toArray
    val idx = vocab.toArray
    for (i <- arr.indices) {
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

    log.info("Wrote {}, {}", outDir.resolve("token_embeddings.csv").toString, outDir.resolve("neighbors.csv").toString)
    println(s"Wrote: ${outDir.resolve("token_embeddings.csv")} and ${outDir.resolve("neighbors.csv")}")
  }
}
