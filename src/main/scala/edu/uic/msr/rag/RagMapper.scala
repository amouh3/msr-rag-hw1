package edu.uic.msr.rag

import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory
import io.circe.Json
import java.security.MessageDigest

/**
 * RagMapper:
 *  - Input: each line is an absolute PDF URI/path (file://, s3a://, hdfs:// all supported by Pdfs.readText)
 *  - Steps:
 *      1) Read PDF text
 *      2) Chunk via project Chunker (maxChars/overlap from Hadoop conf)
 *      3) Sanitize and filter noisy/corrupted chunks
 *      4) Batch-embed with Ollama (batch size from conf)
 *      5) Emit JSON per chunk keyed by a shard (doc hash mod reducers)
 *  - Output key: reducer shard id (IntWritable)
 *  - Output val: JSON line with doc_id/chunk_id/text/hash/ts/vec
 *
 * Logging:
 *  - INFO on high-level milestones per document
 *  - DEBUG on sizes, counts, example dims
 *  - WARN on empty/dropped content
 */
class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {
  private val log    = LoggerFactory.getLogger(getClass)
  private val outKey = new IntWritable()
  private val outVal = new Text()

  /** Normalize control characters and whitespace. */
  private def sanitizeText(s: String): String =
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  /** Heuristic: keep chunks with at least 20% letters and length >= 80. */
  private def isMostlyText(s: String): Boolean = {
    val letters = s.count(_.isLetter)
    s.length >= 80 && (letters.toDouble / math.max(1, s.length)) >= 0.20
  }

  /** Stable id component for chunks. */
  private def sha1Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(s.getBytes("UTF-8"))
    md.digest.map("%02x".format(_)).mkString
  }

  override def map(key: LongWritable, value: Text,
                   ctx: Mapper[LongWritable, Text, IntWritable, Text]#Context): Unit = {

    // ---- configuration knobs (all from Hadoop conf) ----
    val conf     = ctx.getConfiguration
    val model    = conf.get("msr.embed.model", "mxbai-embed-large")
    val maxChars = conf.getInt("msr.chunk.maxChars", 1000)
    val overlap  = conf.getInt("msr.chunk.overlap", 200)
    val reducers = math.max(conf.getInt("mapreduce.job.reduces", 1), 1)
    val batchSz  = conf.getInt("msr.embed.batch", 16)

    val pdfPath  = value.toString.trim
    if (pdfPath.isEmpty) return

    val docId = java.nio.file.Paths.get(pdfPath).getFileName.toString
    log.info("Mapper: start docId={} path={}", docId, pdfPath)

    // ---- 1) read ----
    val text = Pdfs.readText(pdfPath)
    log.debug("Mapper: read chars={} for {}", Int.box(text.length), docId)

    // ---- 2) chunk + sanitize + filter ----
    val allChunks = Chunker.chunks(text, maxChars, overlap).map(sanitizeText)
    val chunks    = allChunks.filter(s => s.nonEmpty && isMostlyText(s))
    val dropped   = allChunks.size - chunks.size

    if (chunks.isEmpty) {
      log.warn("Mapper: no good chunks for {} (dropped={})", docId, Int.box(dropped))
      return
    } else if (dropped > 0) {
      log.info("Mapper: {} dropped={} kept={}", docId, Int.box(dropped), Int.box(chunks.size))
    } else {
      log.info("Mapper: {} kept={} chunks", docId, Int.box(chunks.size))
    }

    // ---- 3) embed (Ollama endpoint base configurable via conf/env) ----
    val base = Option(conf.get("msr.ollama.host"))
      .orElse(sys.env.get("OLLAMA_HOST"))
      .getOrElse("http://127.0.0.1:11434")
    val client = edu.uic.msr.ollama.Ollama.client(Some(base))

    log.debug("Mapper: embedding {} chunks (model={}, batch={}) for {}", Int.box(chunks.size), model, Int.box(batchSz), docId)
    val vecs: Vector[Array[Float]] =
      client.embedBatch(chunks.toVector, model, batch = batchSz)

    log.debug("Mapper: embedded {} chunks; example dim={}", Int.box(chunks.size), Int.box(vecs.headOption.map(_.length).getOrElse(-1)))

    // ---- 4) partition and emit ----
    val shard = math.abs(docId.hashCode) % reducers
    outKey.set(shard)

    val nowMs = System.currentTimeMillis()
    chunks.zip(vecs).zipWithIndex.foreach { case ((c, e), idx) =>
      val rec: Json = Json.obj(
        "doc_id"   -> Json.fromString(docId),
        "chunk_id" -> Json.fromInt(idx),
        "text"     -> Json.fromString(c),
        "hash"     -> Json.fromString(sha1Hex(c)),
        "ts"       -> Json.fromLong(nowMs),
        "vec"      -> Json.fromValues(e.iterator.map(Json.fromFloatOrNull).toList)
      )
      outVal.set(rec.noSpaces)
      ctx.write(outKey, outVal)
    }

    log.info("Mapper: done docId={} -> chunks={} shard={} model={}", docId, Int.box(chunks.size), Int.box(shard), model)
  }
}
