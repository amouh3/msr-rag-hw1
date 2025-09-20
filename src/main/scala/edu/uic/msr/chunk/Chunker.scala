package edu.uic.msr.chunk
object Chunker:
  private def normalize(s: String): String = s.replaceAll("\\s+"," ").trim
  def chunks(s: String, maxChars: Int = 1000, overlap: Int = 200): Vector[String] =
    val clean = normalize(s)
    def step(i: Int, acc: Vector[String]): Vector[String] =
      if i >= clean.length then acc
      else
        val end   = math.min(i + maxChars, clean.length)
        val slice = clean.substring(i, end)
        val cutIx = slice.lastIndexWhere(ch => ch == '.' || ch == '\n')
        val piece = if cutIx >= (maxChars * 0.6).toInt then slice.substring(0, cutIx + 1) else slice
        val stride = math.max(piece.length - overlap, 1)
        step(i + stride, acc :+ piece)
    step(0, Vector.empty)
