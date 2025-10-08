package edu.uic.msr

import java.nio.file.{Files, Paths}
import org.slf4j.LoggerFactory

import edu.uic.msr.rag.{JobMain, AskLucene}

object RunAll {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // === 0) Read lightweight knobs from system props or fall back to sensible defaults ===
    // - Which MR conf to use for building Lucene shards
    val mrConf   = sys.props.getOrElse("msr.mr.conf", "conf/mr.local.conf")
    // - Where JobMain will write shards (it appends "_mr_out" in your code)
    val outRoot  = sys.props.getOrElse("msr.index.outRoot", "out_mr_out")
    // - The AskLucene question (default matches your requirement text)
    val question = sys.props.getOrElse(
      "msr.ask.question",
      "What does section 3 in the paper on inconsistency claim about consistency?"
    )

    // === 1) Build Lucene index if needed (skip if it already exists) ===
    val shard0 = Paths.get(s"$outRoot/index_shard_0")
    val shardsExist =
      Files.exists(Paths.get(outRoot)) && Files.exists(shard0)

    if (shardsExist) {
      log.info(s"RunAll: detected existing index at $outRoot — skipping build.")
    } else {
      log.info(s"RunAll: building Lucene shards with JobMain using conf='$mrConf' (local mode).")
      // Equivalent of: BuildLucene.run("pdfs","lucene-index","mxbai-embed-large")
      // → Your project’s concrete step is JobMain with mr.local.conf
      JobMain.main(Array("--conf", mrConf, "--mode", "local"))
    }

    // === 2) Ask a question against the shards ===
    log.info(s"RunAll: asking Lucene → \"$question\"")
    // AskLucene reads Typesafe config for index.outRoot/shards/etc.
    // We pass the question as command-line args (AskLucene concatenates them).
    AskLucene.main(Array(question))

    log.info("RunAll: done.")
  }
}
