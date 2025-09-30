package edu.uic.msr.rag

import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory
import io.circe.Json
import java.security.MessageDigest

class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {
  private val log    = LoggerFactory.getLogger(getClass)
  private val outKey = new IntWritable()
  private val outVal = new Text()

  private def sanitizeText(s: String): String =
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  // drop chunks that look like corrupted glyph dumps
  private def isMostlyText(s: String): Boolean = {
    val letters = s.count(_.isLetter)
    s.length >= 80 && (letters.toDouble / math.max(1, s.length)) >= 0.20
  }

  private def sha1Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(s.getBytes("UTF-8"))
    md.digest.map("%02x".format(_)).mkString
  }

  override def map(key: LongWritable, value: Text,
                   ctx: Mapper[LongWritable, Text, IntWritable, Text]#Context): Unit = {

    val conf     = ctx.getConfiguration
    val model    = conf.get("msr.embed.model", "mxbai-embed-large")
    val maxChars = conf.getInt("msr.chunk.maxChars", 1000)
    val overlap  = conf.getInt("msr.chunk.overlap", 200)
    val reducers = math.max(conf.getInt("mapreduce.job.reduces", 1), 1)

    val pdfPath  = value.toString.trim
    if (pdfPath.isEmpty) return

    val docId = java.nio.file.Paths.get(pdfPath).getFileName.toString

    log.info(s"Mapper: reading $pdfPath")
    val text = Pdfs.readText(pdfPath)

    val allChunks = Chunker.chunks(text, maxChars, overlap).map(sanitizeText)
    val chunks    = allChunks.filter(s => s.nonEmpty && isMostlyText(s))

    val dropped = allChunks.size - chunks.size
    if (chunks.isEmpty) {
      log.warn(s"Mapper: no good chunks for $docId (dropped=$dropped)"); return
    } else if (dropped > 0) {
      log.info(s"Mapper: $docId dropped=$dropped kept=${chunks.size}")
    }

    val vecs: Vector[Array[Float]] = Ollama.embed(chunks.toVector, model)
    log.debug(s"Embedded ${chunks.size} chunks; example dim=${vecs.headOption.map(_.length).getOrElse(-1)}")

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

    log.info(s"Mapper: $docId -> chunks=${chunks.size} shard=$shard model=$model")
  }
}
