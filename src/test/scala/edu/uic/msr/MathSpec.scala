package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

/**
 * Cosine math sanity tests:
 *  - identical vectors → ~1.0
 *  - orthogonal vectors → ~0.0
 */
object Cosine {
  /** Cosine similarity; returns 0.0 if either vector has zero norm. */
  def sim(a: Array[Float], b: Array[Float]): Double = {
    val dot = a.indices.map(i => a(i).toDouble * b(i)).sum
    val na  = math.sqrt(a.map(x => x.toDouble * x).sum)
    val nb  = math.sqrt(b.map(x => x.toDouble * x).sum)
    if (na == 0 || nb == 0) 0.0 else dot / (na * nb)
  }
}

class MathSpec extends AnyFunSuite {
  private val log = LoggerFactory.getLogger(getClass)

  test("cosine: identical vectors ~ 1.0") {
    val a = Array(1f,2f,3f)
    val b = Array(1f,2f,3f)
    val s = Cosine.sim(a,b)
    log.debug("MathSpec: identical cos={}", Double.box(s))
    assert(math.abs(s - 1.0) < 1e-6)
  }

  test("cosine: orthogonal ~ 0.0") {
    val a = Array(1f,0f,0f)
    val b = Array(0f,1f,0f)
    val s = Cosine.sim(a,b)
    log.debug("MathSpec: orthogonal cos={}", Double.box(s))
    assert(math.abs(s) < 1e-6)
  }
}
