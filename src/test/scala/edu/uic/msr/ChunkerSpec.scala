package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import edu.uic.msr.chunk.Chunker

class ChunkerSpec extends AnyFunSuite {

  test("chunks: produces non-empty chunks, max length, and reasonable overlap") {
    val txt = ("abc def. " * 1000).trim
    val maxChars = 800
    val overlap  = 320 // request 40% overlap for the test

    val cs = Chunker.chunks(txt, maxChars, overlap)
    assert(cs.nonEmpty)
    assert(cs.forall(_.nonEmpty))
    assert(cs.forall(_.length <= maxChars))

    // measure effective overlap between consecutive chunks
    val overlaps = cs.sliding(2).toVector.collect {
      case Vector(a, b) =>
        // overlap measured as overlap in characters if we advanced by (len(a) - effOverlap)
        // approximate by: effOverlap = a.length + b.length - (a + b deduped).length
        val ab = (a + b).replaceAll("\\s+", " ")
        val joined = (a + b).replaceAll("\\s+", " ")
        // simpler: estimate by stride: effOverlap ≈ a.length - (start step)
        // here we approximate via window math:
        math.max(0, a.length + b.length - joined.length)
    }

    val avgOverlap = if (overlaps.nonEmpty) overlaps.sum.toDouble / overlaps.size else 0.0

    // Allow drift because we cut at sentence boundaries.
    // Require at least ~60% of requested and at most ~140% (cuts can make it grow).
    assert(avgOverlap >= overlap * 0.6, s"$avgOverlap was not >= ${overlap * 0.6}")
    assert(avgOverlap <= math.max(overlap * 1.4, overlap + 200), s"$avgOverlap was too large")
  }

  test("chunks: small text yields single chunk") {
    val small = "abc def."
    val cs = Chunker.chunks(small, maxChars = 1000, overlap = 200)
    assert(cs.size == 1)
    assert(cs.head == "abc def.")
  }
}
