package edu.uic.msr.rag

import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.document.Document
import java.nio.file.Paths
import com.typesafe.config.ConfigFactory
import scala.util.matching.Regex
import org.slf4j.LoggerFactory

/**
 * AskLucene:
 *  - Embeds a question, retrieves top-k Lucene vector hits across shards, applies guards,
 *    and optionally generates an answer with citations.
 *  - All configuration comes from Typesafe Config (application.conf).
 *
 * Logging:
 *  - INFO: startup, config snapshot (key knobs), retrieval/generation summaries
 *  - DEBUG: per-shard results, scores, guard thresholds, prompt-size
 *  - ERROR: fatal errors surfaced via Left(err)
 *
 * NOTE: Existing println diagnostics are left intact as requested.
 */
object AskLucene {

  private val log = LoggerFactory.getLogger(getClass)

  // ---- config ----
  private val cfg = ConfigFactory.load()
  private val embedModel = cfg.getString("embed.model")
  private val answerModel = cfg.getString("answer.model")
  private val outRoot = cfg.getString("index.outRoot")
  private val shards = cfg.getInt("index.shards")

  // knobs (tune as needed; you can move these to application.conf)
  private val askCfg           = cfg.getConfig("ask")
  private val kDefault         = askCfg.getInt("k")
  private val perShardFetch    = askCfg.getInt("perShardFetch")
  private val minTopScore      = askCfg.getDouble("minTopScore")
  private val minKeywordOverlap= askCfg.getInt("minKeywordOverlap")
  private val temperature      = askCfg.getDouble("temperature")
  private val numCtxTokens     = askCfg.getInt("numCtxTokens")
  private def refuse: String   = askCfg.getString("refusal")

  log.info(
    "AskLucene config: embedModel={}, answerModel={}, outRoot={}, shards={}, k={}, perShardFetch={}, minTopScore={}, minKeywordOverlap={}, temperature={}, numCtxTokens={}",
    embedModel, answerModel, outRoot, Int.box(shards), Int.box(kDefault), Int.box(perShardFetch),
    Double.box(minTopScore), Int.box(minKeywordOverlap), Double.box(temperature), Int.box(numCtxTokens)
  )

  // ---- helpers ----
  private def open(dir: String): IndexSearcher =
    new IndexSearcher(DirectoryReader.open(FSDirectory.open(Paths.get(dir))))

  private def shardDirs: Vector[String] =
    (0 until shards).toVector.map(i => s"$outRoot/index_shard_$i")

  private final case class Hit(text: String, label: String, score: Float)

  /** Retrieve top-k across shards with scores + labels. */
  private def retrieve(qVec: Vector[Float], k: Int): Vector[Hit] = {
    log.info("retrieve: start (k={}, shards={})", Int.box(k), Int.box(shards))
    val q = qVec.toArray
    val hits: Vector[Hit] = shardDirs.flatMap { dir =>
      val t0 = System.nanoTime()
      val s = open(dir)
      try {
        val per = perShardFetch.max(k)
        log.debug("retrieve: shard={}, perShardFetch={}, queryDim={}", dir, Int.box(per), Int.box(q.length))
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
        log.debug("retrieve: shard done ({} hits, ~{} ms) — {}", Int.box(res.size), Double.box(dtMs), dir)
        res
      } finally s.getIndexReader.close()
    }
    val top = hits.sortBy(-_.score).take(k)
    log.info("retrieve: done (totalCandidates={}, returnedTopK={})", Int.box(hits.size), Int.box(top.size))
    top
  }

  private val stop = Set(
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by", "from",
    "what", "who", "when", "where", "why", "how", "is", "are", "was", "were", "be", "does", "do", "about"
  )

  /** Tokenize to lowercase alnum words; filter empties. */
  private def tokenizeLower(s: String): Vector[String] =
    s.toLowerCase.replaceAll("[^a-z0-9]+", " ").trim.split("\\s+").toVector.filter(_.nonEmpty)

  /** Overlap of non-stopword tokens between question and concatenated contexts. */
  private def keywordOverlapCount(question: String, contexts: Seq[String]): Int = {
    val qToks = tokenizeLower(question).filterNot(stop)
    val ctxToks = tokenizeLower(contexts.mkString(" ")).filterNot(stop).toSet
    val count = qToks.count(ctxToks.contains)
    log.debug("keywordOverlapCount: qTokensNonStop={}, overlap={}", Int.box(qToks.size), Int.box(count))
    count
  }

  //  private def refuse: String = "I don't know based on the MSR corpus."

  private val citationRe: Regex = raw"\[(\d+)\]".r

  /** Remove citation markers that are out of range (keep [1..maxN], drop others). */
  private def sanitizeCitations(text: String, maxN: Int): String =
    citationRe.replaceAllIn(text, m => {
      val n = m.group(1).toInt
      if (n >= 1 && n <= maxN) m.matched else ""
    })

  // ---- main ----
  def main(args: Array[String]): Unit = {
    val question = if (args.nonEmpty) args.mkString(" ") else "What does RagMapper do?"
    log.info("AskLucene.main: question='{}'", question)

    val result: Either[String, (String, Vector[Hit])] = for {
      // 1) embed question
      qEmb <- {
        val t0 = System.nanoTime()
        val r = OllamaClient.embed(embedModel, question)
        val dtMs = (System.nanoTime() - t0) / 1e6
        log.info("embedding: model='{}', took ~{} ms, dim={}", embedModel, Double.box(dtMs), Int.box(r.map(_.length).getOrElse(0)))
        r
      }

      // 2) retrieve evidence
      hits = {
        val h = retrieve(qEmb.map(_.toFloat), k = kDefault)
        log.info("retrieval: got {} hits (k={})", Int.box(h.size), Int.box(kDefault))
        h
      }

      // debug: show retrieved chunks
      _ = {
        println("\n[DEBUG] Retrieved chunks (sorted by score):")
        hits.zipWithIndex.foreach { case (h, i) =>
          val preview = h.text.replaceAll("\\s+", " ").take(160)
          println(f"  [${i + 1}] ${h.label}  score=${h.score}%.3f  $preview%s ...")
        }
      }

      // 3) guards
      topScore = hits.headOption.map(_.score).getOrElse(0f)
      overlap = keywordOverlapCount(question, hits.map(_.text))
      _ = {
        log.debug("guards: topScore={}, minTopScore={}, overlap={}, minOverlap={}",
          Float.box(topScore), Double.box(minTopScore), Int.box(overlap), Int.box(minKeywordOverlap))
        println(f"[DEBUG] topScore=$topScore%.3f  keywordOverlap=$overlap  (minScore=$minTopScore%.2f, minOverlap=$minKeywordOverlap)")
      }

      // 4) either refuse or call generator
      answer <- {
        if (hits.isEmpty || topScore < minTopScore || overlap < minKeywordOverlap) {
          log.info("decision: refusing to answer (hitsEmpty?={}, topScoreOk?={}, overlapOk?={})",
            Boolean.box(hits.isEmpty), Boolean.box(topScore >= minTopScore), Boolean.box(overlap >= minKeywordOverlap))
          Right(refuse)
        } else {
          val prompt = PromptBuilder.build(question, hits.map(_.text))
          log.debug("generation: prompt chars={}, temperature={}, numCtxTokens={}",
            Int.box(prompt.length), Double.box(temperature), Int.box(numCtxTokens))
          val t0 = System.nanoTime()
          OllamaClient.generate(
            model  = answerModel,
            prompt = prompt,
            opts   = OllamaClient.GenerateOptions(num_ctx = numCtxTokens, temperature = temperature)
          ).map { raw =>
            val dtMs = (System.nanoTime() - t0) / 1e6
            log.info("generation: model='{}', took ~{} ms, rawLen={}", answerModel, Double.box(dtMs), Int.box(raw.length))
            val cleaned = raw
              // remove an isolated refusal line if the model appended it
              .replaceAll("(?m)^\\s*I don't know based on the MSR corpus\\.?\\s*$", "")
              // also trim any extra blank lines left behind
              .replaceAll("(?m)^(\\s*\\n){2,}", "\n")
              .trim
            sanitizeCitations(cleaned, hits.size)
          }
        }
      }
    } yield (answer, hits)

    result match {
      case Right((text, hits)) =>
        log.info("done: success (answerLen={}, sources={})", Int.box(text.length), Int.box(hits.length))
        println(s"\n=== ANSWER ===\n$text\n")
        println("Sources:")
        hits.zipWithIndex.foreach { case (h, i) => println(f"[${i + 1}] ${h.label}") }

      case Left(err) =>
        log.error("done: failure err={}", err)
        System.err.println(s"Error: $err")
    }
  }
}
