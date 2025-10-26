package edu.uic.msr.stats

import com.typesafe.config.ConfigFactory
import scala.io.Source
import java.nio.file.{Files, Paths, StandardOpenOption}
import edu.uic.msr.pdf.Pdfs
import edu.uic.msr.chunk.Chunker
import org.slf4j.LoggerFactory

/**
 * VocabExport:
 *  - Reads a list of PDF paths (io.pdfListFile), extracts text, chunks, tokenizes, and counts token frequencies.
 *  - Writes stats.outputDir/vocab.csv with columns: token,token_id,freq
 *
 * Logging:
 *  - INFO: config in/out paths, number of PDFs, final vocab size
 *  - DEBUG: per-PDF token counts (omitted to avoid noise, but easy to add)
 */
object VocabExport {
  private val log = LoggerFactory.getLogger(getClass)

  private def normalize(s: String): String = s.toLowerCase
  private val tokenRx = """[a-zA-Z][a-zA-Z0-9_\-]+""".r

  def main(args: Array[String]): Unit = {
    val confPath = if (args.contains("--conf")) args(args.indexOf("--conf")+1) else "conf/local.conf"
    val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()

    val listPath   = cfg.getString("io.pdfListFile")
    val outDir     = Paths.get(cfg.getString("stats.outputDir"))
    Files.createDirectories(outDir)

    log.info("VocabExport: conf='{}', list='{}', outDir='{}'", confPath, listPath, outDir.toString)

    // 1) read a manageable slice of PDFs (or all—your call)
    val pdfs = Source.fromFile(listPath).getLines().map(_.replace("\uFEFF","").trim).filter(_.nonEmpty).toVector
    log.info("VocabExport: loaded {} input PDF path(s)", Int.box(pdfs.size))

    // 2) extract + chunk + tokenize + count
    val counts = scala.collection.mutable.HashMap.empty[String, Long].withDefaultValue(0L)
    pdfs.foreach { p =>
      val text   = Pdfs.readText(p)
      val chunks = Chunker.chunks(text, 1200, 240) // re-use your chunker
      chunks.foreach { c =>
        tokenRx.findAllIn(normalize(c)).foreach { t =>
          if (t.length >= 2 && t.length <= 40) counts(t) += 1
        }
      }
    }

    // 3) assign token ids and write vocab.csv
    val vocab = counts.toVector.sortBy{case (_,f)=> -f}
    val csv   = new StringBuilder
    csv.append("token,token_id,freq\n")
    vocab.zipWithIndex.foreach { case ((tok,f), id) =>
      csv.append(s"$tok,$id,$f\n")
    }
    val out = outDir.resolve("vocab.csv")
    Files.write(out, csv.result().getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    println(s"Wrote ${vocab.size} rows → ${out.toAbsolutePath}")
  }
}
