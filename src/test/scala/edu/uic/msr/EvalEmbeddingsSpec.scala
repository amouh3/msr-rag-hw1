package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Paths, Path, StandardOpenOption}
import scala.jdk.CollectionConverters._
import scala.util.Try
import org.slf4j.LoggerFactory

/**
 * EvalEmbeddings (spec/CLI variant):
 *  - Reads token_embeddings.csv and writes similarity.csv and analogy.csv
 *  - Logs are INFO-light; stdout/exit behavior preserved for tests.
 */
object EvalEmbeddings {

  private val log = LoggerFactory.getLogger(getClass)

  // ---- small, fast math helpers (vectors may be truncated to common length) ----
  private def dot(a: Array[Float], b: Array[Float]): Double = {
    val n = math.min(a.length, b.length)
    a.iterator.take(n).zip(b.iterator).map { case (x, y) => x * y }.sum
  }
  private def l2(a: Array[Float]): Double =
    math.sqrt(dot(a, a))
  private def cosine(a: Array[Float], b: Array[Float]): Double = {
    val na = l2(a); val nb = l2(b)
    if (na == 0.0 || nb == 0.0) 0.0 else dot(a, b) / (na * nb)
  }
  private def add(a: Array[Float], b: Array[Float]): Array[Float] = {
    val n = math.min(a.length, b.length)
    Array.tabulate(n)(i => (a(i) + b(i)).toFloat)
  }
  private def sub(a: Array[Float], b: Array[Float]): Array[Float] = {
    val n = math.min(a.length, b.length)
    Array.tabulate(n)(i => (a(i) - b(i)).toFloat)
  }

  // ---- lightweight arg parsing ----
  private def argAfter(flag: String, args: Array[String]): Option[String] =
    args.sliding(2).collectFirst { case Array(f, v) if f == flag => v }

  // Parse a row of dim floats; if any element fails, return None (row skipped).
  private def parseVec(cols: Array[String], dim: Int): Option[Array[Float]] = {
    val init: Option[Vector[Float]] = Some(Vector.empty)
    val maybeVals =
      (0 until dim).foldLeft(init) { (acc, i) =>
        acc.flatMap(v => Try(cols(i + 1).toFloat).toOption.map(f => v :+ f))
      }
    maybeVals.map(_.toArray)
  }

  def main(args: Array[String]): Unit = {
    // 1) Resolve output directory (where token_embeddings.csv lives / where we will write *.csv)
    val outDir: Path = {
      argAfter("--outDir", args) match {
        case Some(p) => Paths.get(p.replace("\\", "/"))
        case None =>
          val confPath = argAfter("--conf", args).getOrElse("conf/local.conf")
          log.info("EvalEmbeddings: using conf='{}'", confPath)
          val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
          Paths.get(cfg.getString("stats.outputDir").replace("\\", "/"))
      }
    }

    Files.createDirectories(outDir)
    log.info("EvalEmbeddings: outDir='{}'", outDir.toAbsolutePath.toString)

    // 2) Load token embeddings
    val embFile = outDir.resolve("token_embeddings.csv")
    if (!Files.exists(embFile)) {
      System.err.println(s"[ERROR] Missing file: ${embFile.toAbsolutePath}")
      System.err.println("        Run TokenEmbedAndNeighbors first (Step 2) to produce token_embeddings.csv.")
      sys.exit(2)
    }

    val lines = Files.readAllLines(embFile).asScala.toVector
    if (lines.isEmpty) {
      System.err.println(s"[ERROR] Empty file: ${embFile.toAbsolutePath}")
      sys.exit(3)
    }

    // Format: token,d0,d1,...,d{dim-1}
    val header = lines.head.split(",").toVector
    if (header.isEmpty || header.head != "token") {
      System.err.println(s"[ERROR] token_embeddings.csv header must start with 'token'")
      sys.exit(4)
    }
    val dim = header.size - 1
    if (dim <= 0) {
      System.err.println(s"[ERROR] token_embeddings.csv reports zero dimensions")
      sys.exit(5)
    }
    log.info("Embeddings header OK: dim={}", Int.box(dim))

    val embs: Map[String, Array[Float]] =
      lines.tail.flatMap { ln =>
        val cols = ln.split(",", -1) // keep empty fields if any
        if (cols.length == dim + 1) {
          parseVec(cols, dim).map(vec => cols.head -> vec)
        } else None
      }.toMap

    if (embs.isEmpty) {
      System.err.println("[ERROR] No usable rows found in token_embeddings.csv")
      sys.exit(6)
    }
    log.info("Loaded embeddings: {}", Int.box(embs.size))

    // 3) Word Similarity — only keep pairs that exist in the loaded vocab
    val similarPairs = Vector(
      "bug" -> "defect",
      "program" -> "software",
      "code" -> "source",
      "repository" -> "repo",
      "issue" -> "bug"
    ).filter { case (a, b) => embs.contains(a) && embs.contains(b) }
    log.debug("Similarity pairs kept: {}", Int.box(similarPairs.size))

    val simCsv = new StringBuilder("w1,w2,cosine\n")
    similarPairs.foreach { case (a, b) =>
      val s = cosine(embs(a), embs(b))
      simCsv.append(s"$a,$b,$s\n")
    }
    Files.write(
      outDir.resolve("similarity.csv"),
      simCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )

    // 4) Word Analogy: a - b + c ≈ d (nearest neighbor by cosine, excluding a,b,c)
    val analogies = Vector(
      ("developer", "code", "bug", "fix"),
      ("issue", "bug", "patch", "commit"),
      ("project", "repository", "paper", "conference")
    ).filter { case (a, b, c, _) => embs.contains(a) && embs.contains(b) && embs.contains(c) }
    log.debug("Analogy triplets kept: {}", Int.box(analogies.size))

    val toks = embs.keys.toVector
    val vecs = toks.map(embs).toArray

    val anaCsv = new StringBuilder("a,b,c,predicted,cosine\n")
    analogies.foreach { case (a, b, c, _) =>
      val target = add(sub(embs(a), embs(b)), embs(c))
      val bestOpt =
        toks.iterator
          .zip(vecs.iterator)
          .filter { case (t, _) => t != a && t != b && t != c }
          .map     { case (t, v) => (t, cosine(target, v)) }
          .toVector
          .sortBy  { case (_, s) => -s }
          .headOption

      bestOpt.foreach { case (bestTok, bestCos) =>
        anaCsv.append(s"$a,$b,$c,$bestTok,$bestCos\n")
      }
    }
    Files.write(
      outDir.resolve("analogy.csv"),
      anaCsv.result().getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )

    println(s"Wrote: ${outDir.resolve("similarity.csv")} and ${outDir.resolve("analogy.csv")}")
  }
}
