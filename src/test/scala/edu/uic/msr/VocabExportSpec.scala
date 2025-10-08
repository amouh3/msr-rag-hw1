package edu.uic.msr

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets
import edu.uic.msr.stats.VocabExport
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.slf4j.LoggerFactory

/**
 * VocabExportSpec:
 *  - Writes a couple of tiny PDFs, runs VocabExport, and verifies vocab.csv header/body.
 *  - Keeps behavior identical; logs just key steps.
 */
class VocabExportSpec extends AnyFunSuite {

  private val log = LoggerFactory.getLogger(getClass)

  private def writePdf(text: String): java.nio.file.Path = {
    val doc  = new PDDocument()
    val page = new PDPage()
    doc.addPage(page)
    val cs = new PDPageContentStream(doc, page)
    cs.beginText()
    cs.setFont(PDType1Font.HELVETICA, 12)
    cs.newLineAtOffset(50, 700)
    cs.showText(text)
    cs.endText()
    cs.close()
    val p = Files.createTempFile("vocab-export-", ".pdf")
    doc.save(p.toFile); doc.close()
    p
  }

  test("VocabExport writes vocab.csv with token,token_id,freq") {
    val tmpOut   = Files.createTempDirectory("vocab-out-")
    val pdf1     = writePdf("Bug bug bug. Fix code; repository.")
    val pdf2     = writePdf("Code repository; issue issue.")
    val listFile = Files.createTempFile("pdf-list-", ".txt")
    Files.writeString(listFile, s"${pdf1.toString}\n${pdf2.toString}\n", StandardCharsets.UTF_8)
    log.debug("VocabExportSpec: inputs list at {}", listFile.toAbsolutePath.toString)

    // temp HOCON conf
    val confTxt =
      s"""
         |io {
         |  pdfListFile = "${listFile.toString.replace("\\","\\\\")}"
         |}
         |stats {
         |  outputDir = "${tmpOut.toString.replace("\\","\\\\")}"
         |}
         |""".stripMargin
    val confPath = Files.createTempFile("vocab-conf-", ".conf")
    Files.writeString(confPath, confTxt, StandardCharsets.UTF_8)

    // run
    VocabExport.main(Array("--conf", confPath.toString))

    val vocabCsv = tmpOut.resolve("vocab.csv")
    assert(Files.exists(vocabCsv), s"Missing: $vocabCsv")

    val lines = Files.readAllLines(vocabCsv, StandardCharsets.UTF_8)
    assert(!lines.isEmpty)
    assert(lines.get(0) == "token,token_id,freq")

    // spot-check that some expected tokens are present
    val body = lines.subList(1, lines.size())
    assert(body.stream().anyMatch(_.startsWith("bug,")))
    assert(body.stream().anyMatch(_.startsWith("code,")))
    assert(body.stream().anyMatch(_.startsWith("repository,")))

    log.info("VocabExportSpec: validated vocab.csv at {}", vocabCsv.toAbsolutePath.toString)
  }
}
