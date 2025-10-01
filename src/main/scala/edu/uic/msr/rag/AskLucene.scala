package edu.uic.msr.rag

import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.document.Document
import java.nio.file.Paths
import com.typesafe.config.ConfigFactory
import scala.util.matching.Regex

object AskLucene {

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


  // ---- helpers ----
  private def open(dir: String): IndexSearcher =
    new IndexSearcher(DirectoryReader.open(FSDirectory.open(Paths.get(dir))))

  private def shardDirs: Vector[String] =
    (0 until shards).toVector.map(i => s"$outRoot/index_shard_$i")

  private final case class Hit(text: String, label: String, score: Float)

  /** Retrieve top-k across shards with scores + labels. */
  private def retrieve(qVec: Vector[Float], k: Int): Vector[Hit] = {
    val q = qVec.toArray
    val hits: Vector[Hit] = shardDirs.flatMap { dir =>
      val s = open(dir)
      try {
        val per = perShardFetch.max(k)
        val sd = s.search(new KnnFloatVectorQuery("vec", q, per), per).scoreDocs
        sd.toVector.flatMap { d =>
          val doc: Document = s.doc(d.doc)
          val text = Option(doc.get("text")).getOrElse("")
          if (text.isEmpty) None
          else {
            val docId = Option(doc.get("doc_id")).getOrElse("unknown.pdf")
            val chunk = Option(doc.get("chunk_id")).getOrElse("?")
            Some(Hit(text = text, label = s"$docId#$chunk", score = d.score))
          }
        }
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

//  private def refuse: String = "I don't know based on the MSR corpus."

  private val citationRe: Regex = raw"\[(\d+)\]".r

  private def sanitizeCitations(text: String, maxN: Int): String =
    citationRe.replaceAllIn(text, m => {
      val n = m.group(1).toInt
      if (n >= 1 && n <= maxN) m.matched else ""
    })

  // ---- main ----
  def main(args: Array[String]): Unit = {
    val question = if (args.nonEmpty) args.mkString(" ") else "What does RagMapper do?"

    val result: Either[String, (String, Vector[Hit])] = for {
      // 1) embed question
      qEmb <- OllamaClient.embed(embedModel, question)

      // 2) retrieve evidence
      hits = retrieve(qEmb.map(_.toFloat), k = kDefault)

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
      _ = println(f"[DEBUG] topScore=$topScore%.3f  keywordOverlap=$overlap  (minScore=$minTopScore%.2f, minOverlap=$minKeywordOverlap)")

      // 4) either refuse or call generator
      answer <- {
        if (hits.isEmpty || topScore < minTopScore || overlap < minKeywordOverlap)
          Right(refuse)
        else {
          val prompt = PromptBuilder.build(question, hits.map(_.text))
          OllamaClient.generate(
            model  = answerModel,
            prompt = prompt,
            opts   = OllamaClient.GenerateOptions(num_ctx = numCtxTokens, temperature = temperature)
          ).map { raw =>
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
        println(s"\n=== ANSWER ===\n$text\n")
        println("Sources:")
        hits.zipWithIndex.foreach { case (h, i) => println(f"[${i + 1}] ${h.label}") }

      case Left(err) =>
        System.err.println(s"Error: $err")
    }
  }
}
