package edu.uic.msr.chunk

import org.slf4j.LoggerFactory

/**
 * Fixed-window text chunker used by the pipeline.
 *
 * Behavior:
 * - Normalizes whitespace to single spaces and trims ends.
 * - If the cleaned input is empty: returns Vector.empty.
 * - If length <= maxChars: returns a single chunk = whole string.
 * - Otherwise: emits fixed-size windows of size `maxChars`, advancing by `stride = maxChars - overlap`
 *   (deterministic; last chunk may be shorter).
 *
 * Notes:
 * - `overlap` may be >= maxChars; in that case stride is clamped to 1 (heavy overlap).
 * - The function is pure and deterministic.
 */
object Chunker {
  private val log = LoggerFactory.getLogger(getClass)

  /** Collapse runs of whitespace to a single space, then trim.
   * This reduces chunk-edge artifacts without altering the semantic content much.
   */
  private def normalize(s: String): String =
    s.replaceAll("\\s+", " ").trim

  /** Fixed-window chunking:
   *  - Window length = maxChars (last may be shorter)
   *  - Overlap ~ overlap (deterministic: stride = maxChars - overlap)
   */
  def chunks(s: String, maxChars: Int = 1000, overlap: Int = 200): Vector[String] = {
    log.debug(s"Chunker.chunks(start): rawLen={}, maxChars={}, overlap={}", Int.box(s.length), Int.box(maxChars), Int.box(overlap))
    val clean = normalize(s)
    log.trace("Chunker.chunks: normalizedLen={}", Int.box(clean.length))

    if (clean.isEmpty) {
      log.info("Chunker.chunks: input empty after normalization; returning 0 chunks")
      return Vector.empty
    }
    if (clean.length <= maxChars) {
      log.info("Chunker.chunks: input <= maxChars; returning 1 chunk")
      return Vector(clean)
    }

    val stride = math.max(maxChars - overlap, 1)
    log.debug("Chunker.chunks: computed stride={}", Int.box(stride))

    val n = clean.length

    val indices = Stream.iterate(0)(_ + stride).takeWhile(_ < n)

    val res = indices.map { i =>
      val end = math.min(i + maxChars, n)
      clean.substring(i, end)
    }.toVector

    log.info("Chunker.chunks(done): produced {} chunks (maxChars={}, overlap={}, stride={})",
      Int.box(res.length), Int.box(maxChars), Int.box(overlap), Int.box(stride))

    res
  }
}
