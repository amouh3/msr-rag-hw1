package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite

object Cosine {
  def sim(a: Array[Float], b: Array[Float]): Double = {
    val dot = a.indices.map(i => a(i).toDouble * b(i)).sum
    val na  = math.sqrt(a.map(x => x.toDouble * x).sum)
    val nb  = math.sqrt(b.map(x => x.toDouble * x).sum)
    if (na == 0 || nb == 0) 0.0 else dot / (na * nb)
  }
}

class MathSpec extends AnyFunSuite {
  test("cosine: identical vectors ~ 1.0") {
    val a = Array(1f,2f,3f)
    val b = Array(1f,2f,3f)
    assert(math.abs(Cosine.sim(a,b) - 1.0) < 1e-6)
  }
  test("cosine: orthogonal ~ 0.0") {
    val a = Array(1f,0f,0f)
    val b = Array(0f,1f,0f)
    assert(math.abs(Cosine.sim(a,b)) < 1e-6)
  }
}
