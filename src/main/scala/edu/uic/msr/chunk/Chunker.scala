package edu.uic.msr.chunk

object Chunker {
  private def normalize(s: String): String =
    s.replaceAll("\\s+", " ").trim

  /** Fixed-window chunking:
   * - Window length = maxChars (last may be shorter)
   * - Overlap ~ overlap (deterministic: stride = maxChars - overlap)
   */
  def chunks(s: String, maxChars: Int = 1000, overlap: Int = 200): Vector[String] = {
    val clean = normalize(s)
    if (clean.isEmpty) return Vector.empty
    if (clean.length <= maxChars) return Vector(clean)

    val stride = math.max(maxChars - overlap, 1)
    val out = Vector.newBuilder[String]
    var i = 0
    val n = clean.length
    while (i < n) {
      val end = math.min(i + maxChars, n)
      out += clean.substring(i, end)
      if (end == n) { // reached the end
        i = n
      } else {
        i = i + stride
      }
    }
    out.result()
  }
}
