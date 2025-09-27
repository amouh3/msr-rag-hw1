package edu.uic.msr.rag

import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import edu.uic.msr.ollama.Ollama
import org.slf4j.LoggerFactory
import io.circe.Json

/** Mapper
 * input value: ABSOLUTE PDF PATH per line
 * map out: key = shard id, value = JSON {doc_id, chunk_id, text, vec}
 */
class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {

  private val log    = LoggerFactory.getLogger(getClass)
  private val outKey = new IntWritable()
  private val outVal = new Text()

  private def sanitizeText(s: String): String =
    // remove control chars except tab/newline, collapse whitespace
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ")
      .replaceAll("""\s+""", " ")
      .trim

  override def map(key: LongWritable,
                   value: Text,
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
    val text   = Pdfs.readText(pdfPath)
    val chunks = Chunker.chunks(text, maxChars, overlap)
      .map(sanitizeText)
      .filter(_.nonEmpty)

    if (chunks.isEmpty) {
      log.warn(s"Mapper: no chunks for $docId"); return
    }

    // One call to embed all chunks (simple & fine for local mode)
    val vecs: Vector[Array[Float]] = Ollama.embed(chunks.toVector, model)
    log.debug(s"Embedded ${chunks.size} chunks; example dim=${vecs.headOption.map(_.length).getOrElse(-1)}")

    val shard = math.abs(docId.hashCode) % reducers
    outKey.set(shard)

    chunks.zip(vecs).zipWithIndex.foreach { case ((c, e), idx) =>
      val rec: Json = Json.obj(
        "doc_id"   -> Json.fromString(docId),
        "chunk_id" -> Json.fromInt(idx),
        "text"     -> Json.fromString(c),
        // fromValues needs an Iterable[Json]; convert iterator -> List
        "vec"      -> Json.fromValues(e.iterator.map(Json.fromFloatOrNull).toList)
      )
      outVal.set(rec.noSpaces) // safe JSON string
      ctx.write(outKey, outVal)
    }

    log.info(s"Mapper: $docId -> chunks=${chunks.size} shard=$shard model=$model")
  }
}
