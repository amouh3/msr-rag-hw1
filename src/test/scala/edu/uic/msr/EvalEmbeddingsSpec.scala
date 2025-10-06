package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Paths, Path, StandardOpenOption}
import scala.jdk.CollectionConverters._
import scala.util.Try

object EvalEmbeddings {

  // ---- small, fast math helpers (vectors are expected to be same dim) ----
  private def dot(a: Array[Float], b: Array[Float]): Double = {
    var s = 0.0
    var i = 0
    val n = math.min(a.length, b.length)
    while (i < n) { s += a(i) * b(i); i += 1 }
    s
  }
  private def l2(a: Array[Float]): Double = math.sqrt(dot(a, a))
  private def cosine(a: Array[Float], b: Array[Float]): Double = {
    val na = l2(a); val nb = l2(b)
    if (na == 0.0 || nb == 0.0) 0.0 else dot(a, b) / (na * nb)
  }
  private def add(a: Array[Float], b: Array[Float]): Array[Float] = {
    val n = math.min(a.length, b.length)
    val out = new Array[Float](n)
    var i = 0
    while (i < n) { out(i) = (a(i) + b(i)).toFloat; i += 1 }
    out
  }
  private def sub(a: Array[Float], b: Array[Float]): Array[Float] = {
    val n = math.min(a.length, b.length)
    val out = new Array[Float](n)
    var i = 0
    while (i < n) { out(i) = (a(i) - b(i)).toFloat; i += 1 }
    out
  }

  // ---- lightweight arg parsing ----
  private def argAfter(flag: String, args: Array[String]): Option[String] =
    args.sliding(2).collectFirst { case Array(f, v) if f == flag => v }

  def main(args: Array[String]): Unit = {
    // 1) Resolve output directory (where token_embeddings.csv lives / where we will write *.csv)
    //    Priority: --outDir arg > stats.outputDir in conf (from --conf) > error
    val outDir: Path = {
      argAfter("--outDir", args) match {
        case Some(p) =>
          // Robust on Windows: normalize slashes so tests don't need to care
          Paths.get(p.replace("\\", "/"))
        case None =>
          val confPath = argAfter("--conf", args).getOrElse("conf/local.conf")
          val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()
          Paths.get(cfg.getString("stats.outputDir").replace("\\", "/"))
      }
    }

    Files.createDirectories(outDir)

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

    val embs: Map[String, Array[Float]] = lines.tail.flatMap { ln =>
      val cols = ln.split(",", -1) // keep empty fields if any
      if (cols.length == dim + 1) {
        val tok = cols.head
        val vec = new Array[Float](dim)
        var ok  = true
        var i   = 0
        while (i < dim && ok) {
          ok = Try(cols(i + 1).toFloat).toOption.exists { f =>
            vec(i) = f; true
          }
          i += 1
        }
        if (ok) Some(tok -> vec) else None
      } else None
    }.toMap

    if (embs.isEmpty) {
      System.err.println("[ERROR] No usable rows found in token_embeddings.csv")
      sys.exit(6)
    }

    // 3) Word Similarity — only keep pairs that exist in the loaded vocab
    val similarPairs = Vector(
      "bug" -> "defect",
      "program" -> "software",
      "code" -> "source",
      "repository" -> "repo",
      "issue" -> "bug"
    ).filter { case (a, b) => embs.contains(a) && embs.contains(b) }

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

    val toks = embs.keys.toVector
    val vecs = toks.map(embs).toArray

    val anaCsv = new StringBuilder("a,b,c,predicted,cosine\n")
    analogies.foreach { case (a, b, c, _expected) =>
      val target = add(sub(embs(a), embs(b)), embs(c))
      // brute-force nearest neighbor
      var bestTok = ""
      var bestCos = Double.NegativeInfinity
      var i       = 0
      while (i < vecs.length) {
        val t = toks(i)
        if (t != a && t != b && t != c) {
          val sc = cosine(target, vecs(i))
          if (sc > bestCos) { bestCos = sc; bestTok = t }
        }
        i += 1
      }
      if (bestTok.nonEmpty && !bestCos.isNegInfinity) {
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
