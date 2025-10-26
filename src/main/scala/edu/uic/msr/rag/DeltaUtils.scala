package edu.uic.msr.rag

import com.typesafe.config.{Config, ConfigFactory}
import java.security.MessageDigest

/** Small helpers + strongly-typed config facade for HW2.
 * Keep these pure so they are easy to test.
 */
object DeltaUtils {

  // -------------------
  // Typed config access
  // -------------------
  final case class Paths(rawDir: String, deltaRoot: String, chkRoot: String)
  final case class Tables(docs: String, chunks: String, embeddings: String, index: String)
  final case class Embedder(name: String, version: String, batchSize: Int)

  final case class AppConf(
                            sparkMaster: String,
                            paths: Paths,
                            tables: Tables,
                            embedder: Embedder,
                            chunkMaxChars: Int,
                            chunkOverlap: Int
                          )

  /** Load HOCON config. Pass `-Dconfig.file=conf/spark.local.conf` or keep default. */
  def loadConf(path: Option[String] = None): AppConf = {
    val c: Config = path match {
      case Some(p) =>
        val f = new java.io.File(p)
        if (f.exists()) ConfigFactory.parseFile(f).resolve()
        else ConfigFactory.load()
      case None =>
        // Respect -Dconfig.file if provided, otherwise fall back
        if (System.getProperty("config.file") != null)
          ConfigFactory.parseFile(new java.io.File(System.getProperty("config.file"))).resolve()
        else
          ConfigFactory.load()
    }

    AppConf(
      sparkMaster = c.getString("spark.master"),
      paths = Paths(
        rawDir    = c.getString("paths.rawDir"),
        deltaRoot = c.getString("paths.deltaRoot"),
        chkRoot   = c.getString("paths.chkRoot")
      ),
      tables = Tables(
        docs       = c.getString("tables.docs"),
        chunks     = c.getString("tables.chunks"),
        embeddings = c.getString("tables.embeddings"),
        index      = c.getString("tables.index")
      ),
      embedder = Embedder(
        name      = c.getString("embedder.name"),
        version   = c.getString("embedder.version"),
        batchSize = c.getInt("embedder.batchSize")
      ),
      chunkMaxChars = c.getInt("chunk.maxChars"),
      chunkOverlap  = c.getInt("chunk.overlap")
    )
  }


  // -------------------
  // Small pure helpers
  // -------------------
  def sha256Hex(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s.getBytes("UTF-8"))
    md.digest.map("%02x".format(_)).mkString
  }

  /** Very conservative whitespace normalization. (You can swap in your Pdfs.normalize later.) */
  def normalizeWhitespace(s: String): String =
    s.replaceAll("""[\p{Cntrl}&&[^\t\n]]""", " ").replaceAll("""\s+""", " ").trim

  /** Placeholder language detection, will wire proper detection later. */
  def detectLang(s: String): String = "en"

  /** Title heuristic stub; replace with your HW1 helper if you prefer. */
  def firstLineOrName(text: String, uri: String): String = {
    val first = text.linesIterator.take(1).mkString.trim
    if (first.nonEmpty) first else uri.split("[/\\\\]").lastOption.getOrElse(uri)
  }
}
