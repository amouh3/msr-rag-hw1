package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import io.circe.parser._
import io.circe.Json

class JsonSpec extends AnyFunSuite {
  test("mapper JSON: doc_id/chunk_id/text/vec shape and no control chars") {
    val jsonStr =
      """{"doc_id":"foo.pdf","chunk_id":0,"text":"Hello world.","vec":[0.1,0.2,0.3]}"""
    val j = parse(jsonStr).toOption.get
    val cur = j.hcursor

    assert(cur.get[String]("doc_id").toOption.contains("foo.pdf"))
    assert(cur.get[Int]("chunk_id").toOption.contains(0))
    assert(cur.get[String]("text").toOption.exists(_.contains("Hello")))
    val vec = cur.get[Vector[Float]]("vec").toOption.get
    assert(vec.nonEmpty)

    // no control characters in text (except \t \n if present)
    val txt = cur.get[String]("text").toOption.get
    assert(!txt.exists(ch => ch.isControl && ch != '\t' && ch != '\n'))
  }
}
