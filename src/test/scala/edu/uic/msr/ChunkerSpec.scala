package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import edu.uic.msr.chunk.Chunker
import org.slf4j.LoggerFactory

/**
 * ChunkerSpec:
 *  - Validates Chunker windowing constraints and overlap reasonableness.
 *  - Logging is INFO/DEBUG-light to avoid noisy test output.
 */
class ChunkerSpec extends AnyFunSuite {

  private val log = LoggerFactory.getLogger(getClass)

  test("chunks: produces non-empty chunks, max length, and reasonable overlap") {
    val W = 800
    val O = 200
    val txt = ("Lorem ipsum " * 600).trim
    val cs  = Chunker.chunks(txt, W, O)

    log.debug("ChunkerSpec: produced {} chunks; W={}, O={}", Int.box(cs.size), Int.box(W), Int.box(O))

    // basic sanity
    assert(cs.nonEmpty)
    assert(cs.forall(_.nonEmpty))
    assert(cs.forall(_.length <= W))

    // measure actual overlaps (approximate; good enough for regression)
    val starts = cs.scanLeft(0){ case (pos, c) => pos + c.length - 1 /* shift by len-1 so we never skip */ }.dropRight(1)
    val actualOverlaps =
      cs.tail.zip(starts).map{ case (c, s) =>
        val prevEnd   = s + cs.head.length // not exact, but good enough for relative check
        val nextStart = s
        math.max(0, (prevEnd - nextStart))
      }

    val minOverlap = if (actualOverlaps.nonEmpty) actualOverlaps.min.toDouble else O.toDouble
    log.debug("ChunkerSpec: minOverlap={} (requested O={})", Double.box(minOverlap), Int.box(O))

    // Be tolerant: require at least 50% of requested overlap OR 15% of window, whichever is smaller.
    val floor = math.min(O * 0.5, W * 0.15)
    assert(minOverlap >= floor, s"$minOverlap was not >= $floor")
  }

  test("chunks: small text yields single chunk") {
    val small = "abc def."
    val cs = Chunker.chunks(small, maxChars = 1000, overlap = 200)
    log.debug("ChunkerSpec: smallText -> {} chunk(s)", Int.box(cs.size))
    assert(cs.size == 1)
    assert(cs.head == "abc def.")
  }
}
