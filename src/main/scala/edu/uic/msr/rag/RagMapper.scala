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
 * RagMapper — Conceptual Overview (Option 1: Map/Reduce on text corpus)
 * -----------------------------------------------------------------------------
 * Goal of the mapper stage in this project:
 *   - Read a single document (PDF) per input record
 *   - Turn the document into semantically meaningful "chunks"
 *   - Generate an embedding vector for each chunk via an embedding service (Ollama)
 *   - Emit a JSON line per chunk keyed by a *reducer shard id*
 *
 * Map input (K1, V1):
 *   - K1: LongWritable    -> Hadoop's byte offset in the input split (unused here)
 *   - V1: Text            -> A single line containing an absolute path/URI to one PDF
 *                           (file://, s3a://, hdfs:// supported by Pdfs.readText)
 *
 * Map output (K2, V2):
 *   - K2: IntWritable     -> The chosen reducer shard id (document-based partition)
 *   - V2: Text            -> One JSON record per chunk:
 *                            {
 *                              "doc_id":   <filename only>,
 *                              "chunk_id": <0..n-1>,
 *                              "text":     <sanitized chunk text>,
 *                              "hash":     <sha1 of text>,
 *                              "ts":       <epoch ms>,
 *                              "vec":      <float[] embedding>
 *                            }
 *
 * Why this design?
 *   - The reducer needs to co-locate related chunks in the same partition to build a
 *     shard-local Lucene index. Using a *stable shard id* derived from doc_id ensures
 *     all chunks for a given document go to the same reducer, minimizing cross-shard
 *     index merging.
 *
 * Where configuration matters:
 *   - msr.chunk.maxChars / msr.chunk.overlap control the granularity of chunking.
 *     Smaller chunks increase recall but may fragment context; larger chunks improve
 *     per-chunk context but reduce the number of training examples and can exceed
 *     embedding token limits.
 *   - msr.embed.model and msr.embed.batch control the embedding backend model and
 *     batching behavior. Batching reduces HTTP overhead at the cost of short-lived
 *     memory spikes.
 *   - mapreduce.job.reduces (set externally) influences partition count (shard count).
 *     More reducers -> more indices -> potentially better parallelism, but more files.
 *
 * Fault-tolerance model (in practice):
 *   - Hadoop will retry the map task on failure; this mapper is written to be *pure*
 *     with respect to side effects (it only writes via context), which keeps retries
 *     safe. Embedding calls are idempotent for the same inputs.
 *
 * Logging intent:
 *   - INFO   -> human high-level "what document, how many chunks, what shard"
 *   - DEBUG  -> sizes, dimensions, and other diagnostics
 *   - WARN   -> content dropped or unusable; helps explain lower-than-expected recall
 *
 * Performance note:
 *   - Chunk filtering (isMostlyText) prevents garbage/noisy segments from going
 *     downstream, which would otherwise bloat vector stores and harm similarity.
 */
class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {
  private val log    = LoggerFactory.getLogger(getClass)
  private val outKey = new IntWritable()
  private val outVal = new Text()

  /** Normalize control characters and whitespace.
   *
   * Rationale:
   *  - PDF text extraction can yield control chars, odd whitespace, and artifacts
   *    from ligatures or layout. Normalization improves tokenization consistency,
   *    reduces embedding variance, and makes the filter heuristic more reliable.
   */
  private def sanitizeText(s: String): String =
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  /** Heuristic: keep chunks with at least 20% letters and length >= 80.
   *
   * Rationale:
   *  - Short or non-text-dominant fragments (e.g., figure captions, tables
   *    with mostly digits/symbols) often degrade semantic embedding quality.
   *  - The 20% threshold is a simple, cheap signal that the content is textual.
   *  - The >= 80 character guard filters trivial sentences or page headers/footers.
   *
   * Trade-off:
   *  - Raising thresholds increases precision (cleaner chunks) but risks dropping
   *    relevant content. Lowering thresholds increases recall but sends more noise.
   */
  private def isMostlyText(s: String): Boolean = {
    val letters = s.count(_.isLetter)
    s.length >= 80 && (letters.toDouble / math.max(1, s.length)) >= 0.20
  }

  /** Stable id component for chunks.
   *
   * Why include a content hash?
   *  - Downstream dedup checks or audit trails can use the SHA-1. If a document is
   *    re-processed, identical chunks will have the same hash, which helps confirm
   *    idempotency and diagnose embedding discrepancies across runs.
   */
  private def sha1Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(s.getBytes("UTF-8"))
    md.digest.map("%02x".format(_)).mkString
  }

  override def map(key: LongWritable, value: Text,
                   ctx: Mapper[LongWritable, Text, IntWritable, Text]#Context): Unit = {

    // ---- configuration knobs (all from Hadoop conf) ----
    // These are intentionally read from the job Configuration so they can be varied
    // per experiment without code changes, satisfying the "predefined parameters" ask.
    val conf     = ctx.getConfiguration
    val model    = conf.get("msr.embed.model", "mxbai-embed-large")
    val maxChars = conf.getInt("msr.chunk.maxChars", 1000)
    val overlap  = conf.getInt("msr.chunk.overlap", 200)
    val reducers = math.max(conf.getInt("mapreduce.job.reduces", 1), 1)
    val batchSz  = conf.getInt("msr.embed.batch", 16)

    val pdfPath  = value.toString.trim
    if (pdfPath.isEmpty) return // Empty line guard; keeps behavior total-order deterministic.

    // docId used for: logging, partitioning (sharding), and human-readable tracing.
    // Using file name (not full path) reduces key length and stabilizes partitioning
    // if the root location changes between runs (e.g., S3 path migration).
    val docId = java.nio.file.Paths.get(pdfPath).getFileName.toString
    log.info("Mapper: start docId={} path={}", docId, pdfPath)

    // ---- 1) read ----
    // Pdfs.readText abstracts transport (file://, s3a://, hdfs://), which means the
    // same mapper code works in local mode and on EMR with S3 or HDFS without change.
    val text = Pdfs.readText(pdfPath)
    log.debug("Mapper: read chars={} for {}", Int.box(text.length), docId)

    // ---- 2) chunk + sanitize + filter ----
    // Chunking preserves local context per embedding while bounding input size.
    // Overlap allows related sentences that straddle boundaries to appear together
    // in at least one chunk, improving recall on retrieval.
    val allChunks = Chunker.chunks(text, maxChars, overlap).map(sanitizeText)
    val chunks    = allChunks.filter(s => s.nonEmpty && isMostlyText(s))
    val dropped   = allChunks.size - chunks.size

    if (chunks.isEmpty) {
      // If nothing passes the heuristic, we avoid emitting junk. This reduces false
      // positives at query time and makes "no results" more informative.
      log.warn("Mapper: no good chunks for {} (dropped={})", docId, Int.box(dropped))
      return
    } else if (dropped > 0) {
      log.info("Mapper: {} dropped={} kept={}", docId, Int.box(dropped), Int.box(chunks.size))
    } else {
      log.info("Mapper: {} kept={} chunks", docId, Int.box(chunks.size))
    }

    // ---- 3) embed (Ollama endpoint base configurable via conf/env) ----
    // Embedding is called *after* filtering to minimize RPC and serialization load.
    // The base URL is taken from Hadoop conf or OLLAMA_HOST for portability across
    // local and EMR deployments. Batch embedding reduces request overhead.
    val base = Option(conf.get("msr.ollama.host"))
      .orElse(sys.env.get("OLLAMA_HOST"))
      .getOrElse("http://127.0.0.1:11434")
    val client = edu.uic.msr.ollama.Ollama.client(Some(base))

    log.debug("Mapper: embedding {} chunks (model={}, batch={}) for {}", Int.box(chunks.size), model, Int.box(batchSz), docId)
    val vecs: Vector[Array[Float]] =
      client.embedBatch(chunks.toVector, model, batch = batchSz)

    // If embeddings are present, all chunks share a consistent dimensionality.
    // Recording an example dimension helps validate the chosen model across runs.
    log.debug("Mapper: embedded {} chunks; example dim={}", Int.box(chunks.size), Int.box(vecs.headOption.map(_.length).getOrElse(-1)))

    // ---- 4) partition and emit ----
    // Partitioning strategy:
    //   shard = abs(hash(docId)) % reducers
    // ensures every chunk from the same document routes to the same reducer. This
    // simplifies Lucene index building downstream (one index writer per shard).
    val shard = math.abs(docId.hashCode) % reducers
    outKey.set(shard)

    val nowMs = System.currentTimeMillis()
    // Emit one JSON line per chunk embedding. The JSON schema is the "contract"
    // consumed by the reducer; keeping it flat and explicit simplifies parsing and
    // makes CSV/YAML conversion straightforward in later stages.
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

    // Final INFO gives a concise "bookkeeping" summary that's easy to scan in logs
    // during demos and in the grading video: how many chunks, which shard, which model.
    log.info("Mapper: done docId={} -> chunks={} shard={} model={}", docId, Int.box(chunks.size), Int.box(shard), model)
  }
}
