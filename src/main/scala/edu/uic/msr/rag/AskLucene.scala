package edu.uic.msr.rag

import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.document.Document
import java.nio.file.Paths
import com.typesafe.config.ConfigFactory
import scala.util.matching.Regex
import org.slf4j.LoggerFactory
import edu.uic.msr.ollama.OllamaClient
import edu.uic.msr.rag.PromptBuilder

object AskLucene {

  private val log = LoggerFactory.getLogger(getClass)

  // ---- config ----
  private val cfg = ConfigFactory.load()
  private val embedModel = cfg.getString("embed.model")
  private val answerModel = cfg.getString("answer.model")
  private val outRoot = cfg.getString("index.outRoot")
  private val shards = cfg.getInt("index.shards")

  private val askCfg           = cfg.getConfig("ask")
  private val kDefault         = askCfg.getInt("k")
  private val perShardFetch    = askCfg.getInt("perShardFetch")
  private val minTopScore      = askCfg.getDouble("minTopScore")
  private val minKeywordOverlap= askCfg.getInt("minKeywordOverlap")
  private val temperature      = askCfg.getDouble("temperature")
  private val numCtxTokens     = askCfg.getInt("numCtxTokens")
  private def refuse: String   = askCfg.getString("refusal")

  log.info(
    s"AskLucene config: embedModel=$embedModel, answerModel=$answerModel, outRoot=$outRoot, " +
      s"shards=$shards, k=$kDefault, perShardFetch=$perShardFetch, minTopScore=$minTopScore, " +
      s"minKeywordOverlap=$minKeywordOverlap, temperature=$temperature, numCtxTokens=$numCtxTokens"
  )

  private def open(dir: String): IndexSearcher =
    new IndexSearcher(DirectoryReader.open(FSDirectory.open(Paths.get(dir))))

  private def shardDirs: Vector[String] =
    (0 until shards).toVector.map(i => s"$outRoot/index_shard_$i")

  private final case class Hit(text: String, label: String, score: Float)

  /** Retrieve top-k across shards with scores + labels. */
  private def retrieve(qVec: Vector[Float], k: Int): Vector[Hit] = {
    val q = qVec.toArray
    val hits: Vector[Hit] = shardDirs.flatMap { dir =>
      val t0 = System.nanoTime()
      val s = open(dir)
      try {
        val per = perShardFetch.max(k)
        log.debug(s"retrieve: shard=$dir, perShardFetch=$per, queryDim=${q.length}")
        val sd = s.search(new KnnFloatVectorQuery("vec", q, per), per).scoreDocs
        val res = sd.toVector.flatMap { d =>
          val doc: Document = s.doc(d.doc)
          val text = Option(doc.get("text")).getOrElse("")
          if (text.isEmpty) None
          else {
            val docId = Option(doc.get("doc_id")).getOrElse("unknown.pdf")
            val chunk = Option(doc.get("chunk_id")).getOrElse("?")
            Some(Hit(text = text, label = s"$docId#$chunk", score = d.score))
          }
        }
        val dtMs = (System.nanoTime() - t0) / 1e6
        log.debug(s"retrieve: shard done (${res.size} hits, ~$dtMs ms) — $dir")
        res
      } finally s.getIndexReader.close()
    }
    hits.sortBy(-_.score).take(k)
  }

  private val stop = Set(
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by", "from",
    "what", "who", "when", "where", "why", "how", "is", "are", "was", "were", "be", "does", "do", "about"
  )

  private def tokenizeLower(s: String): Vector[String] =
    s.toLowerCase.replaceAll("[^a-z0-9]+", " ").trim.split("\\s+").toVector.filter(_.nonEmpty)

  private def keywordOverlapCount(question: String, contexts: Seq[String]): Int = {
    val qToks = tokenizeLower(question).filterNot(stop)
    val ctxToks = tokenizeLower(contexts.mkString(" ")).filterNot(stop).toSet
    qToks.count(ctxToks.contains)
  }

  private val citationRe: Regex = raw"\[(\d+)\]".r
  private def sanitizeCitations(text: String, maxN: Int): String =
    citationRe.replaceAllIn(text, m => {
      val n = m.group(1).toInt
      if (n >= 1 && n <= maxN) m.matched else ""
    })

  def main(args: Array[String]): Unit = {
    val question = if (args.nonEmpty) args.mkString(" ") else "What does RagMapper do?"
    log.info(s"AskLucene.main: question='$question'")

    val result: Either[String, (String, Vector[Hit])] = for {
      qEmb <- {
        val t0 = System.nanoTime()
        val r = OllamaClient.embed(embedModel, question)
        val dtMs = (System.nanoTime() - t0) / 1e6
        r
      }

      hits = retrieve(qEmb.map(_.toFloat), k = kDefault)

      _ = {
        println("\n[DEBUG] Retrieved chunks (sorted by score):")
        hits.zipWithIndex.foreach { case (h, i) =>
          val preview = h.text.replaceAll("\\s+", " ").take(160)
          println(f"  [${i + 1}] ${h.label}  score=${h.score}%.3f  $preview ...")
        }
      }

      topScore = hits.headOption.map(_.score).getOrElse(0f)
      overlap = keywordOverlapCount(question, hits.map(_.text))
      _ = {
        log.debug(s"guards: topScore=$topScore, minTopScore=$minTopScore, overlap=$overlap, minOverlap=$minKeywordOverlap")
        println(f"[DEBUG] topScore=$topScore%.3f  keywordOverlap=$overlap  (minScore=$minTopScore%.2f, minOverlap=$minKeywordOverlap)")
      }

      answer <- {
        if (hits.isEmpty || topScore < minTopScore || overlap < minKeywordOverlap) {
          log.info(s"decision: refusing to answer (hitsEmpty=${hits.isEmpty}, topScoreOk=${topScore >= minTopScore}, overlapOk=${overlap >= minKeywordOverlap})")
          Right(refuse)
        } else {
          val prompt = PromptBuilder.build(question, hits.map(_.text))
          log.debug(s"generation: prompt chars=${prompt.length}, temperature=$temperature, numCtxTokens=$numCtxTokens")
          val t0 = System.nanoTime()
          OllamaClient.generate(
            model  = answerModel,
            prompt = prompt,
            opts   = OllamaClient.GenerateOptions(num_ctx = numCtxTokens, temperature = temperature)
          ).map { raw =>
            val dtMs = (System.nanoTime() - t0) / 1e6
            log.info(s"generation: model=$answerModel, took ~$dtMs ms, rawLen=${raw.length}")
            val cleaned = raw
              .replaceAll("(?m)^\\s*I don't know based on the MSR corpus\\.?\\s*$", "")
              .replaceAll("(?m)^(\\s*\\n){2,}", "\n")
              .trim
            sanitizeCitations(cleaned, hits.size)
          }
        }
      }
    } yield (answer, hits)

    result match {
      case Right((text, hits)) =>
        println(s"\n=== ANSWER ===\n$text\n")
        println("Sources:")
        hits.zipWithIndex.foreach { case (h, i) => println(f"[${i + 1}] ${h.label}") }

      case Left(err) =>
        System.err.println(s"Error: $err")
    }
  }
}
