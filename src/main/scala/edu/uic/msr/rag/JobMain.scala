package edu.uic.msr.rag

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, TextOutputFormat}
import org.slf4j.LoggerFactory

/** MapReduce driver that builds Lucene shard directories from a list of PDFs.
 *
 * Usage examples:
 *   # local dev (filesystem)
 *   sbt "runMain edu.uic.msr.rag.JobMain --conf conf/mr.local.conf --mode local"
 *
 *   # EMR/YARN with a config bundled in the JAR (src/main/resources/emr-dev.conf)
 *   hadoop jar msr-rag-hw1-assembly.jar \
 *     edu.uic.msr.rag.JobMain \
 *     --conf emr-dev.conf
 *
 * Required config keys (in the selected conf):
 *   mr.inputList   = "file:///.../pdf_list.txt" | "s3a://bucket/input/pdf_list.txt"
 *   mr.outputDir   = "file:///.../index_shards" | "s3a://bucket/outputs/index_shards"
 *   mr.shards      = 4
 *   embed.model    = "mxbai-embed-large"
 * Optional:
 *   embed.maxChars = 1000
 *   embed.overlap  = 200
 *   embed.batch    = 8
 * Notes:
 *   - The job writes to outMr = mr.outputDir + "_mr_out"
 *   - Reducers copy Lucene indexes into msr.output.dir (we set it to outMr)
 */
object JobMain {
  private val log = LoggerFactory.getLogger(getClass)

  /** Load a HOCON file by name (resource inside the JAR) or from the filesystem.
   * Priority: --conf value as resource -> --conf value as file -> application.conf defaults.
   * Accepts plain names like "emr-dev.conf" or paths like "conf/mr.local.conf".
   */
  private def loadConfig(confNameOrPathOpt: Option[String]) = {
    val base = ConfigFactory.load() // application.conf (defaults)

    val name = confNameOrPathOpt.getOrElse("local.conf") // default if none passed
    log.info("JobMain.loadConfig: using name='{}'", name)

    // Try classpath resources first (next to application.conf)
    val cl = Thread.currentThread().getContextClassLoader
    val resourceUrl =
      Option(cl.getResource(name))
        .orElse(Option(cl.getResource(s"conf/$name"))) // allows packaging under /conf as well

    val parsedFromResource = resourceUrl.map(ConfigFactory.parseURL)

    // If not found on classpath, try as a real file on disk (absolute or under ./conf/)
    val parsedFromFile =
      if (parsedFromResource.isEmpty) {
        val f1 = new java.io.File(name)
        val f2 = new java.io.File(s"conf/$name")
        if (f1.exists()) Some(ConfigFactory.parseFile(f1))
        else if (f2.exists()) Some(ConfigFactory.parseFile(f2))
        else None
      } else None

    val parsed = parsedFromResource.orElse(parsedFromFile).getOrElse {
      throw new IllegalArgumentException(
        s"Could not find config '$name' as a classpath resource or file (also tried 'conf/$name')."
      )
    }

    parsed.withFallback(base).resolve()
  }

  def main(args: Array[String]): Unit = {
    // ---- choose config ----
    val cfg = loadConfig(
      if (args.contains("--conf")) Some(args(args.indexOf("--conf") + 1)) else None
    )

    // ---- read config ----
    val input  = cfg.getString("mr.inputList")     // e.g. file:///... or s3a://...
    val outDir = cfg.getString("mr.outputDir")     // logical root (we'll write to <outputDir>_mr_out)
    val shards = cfg.getInt("mr.shards")
    val model  = cfg.getString("embed.model")
    val maxCh  = if (cfg.hasPath("embed.maxChars")) cfg.getInt("embed.maxChars") else 1000
    val ovl    = if (cfg.hasPath("embed.overlap"))  cfg.getInt("embed.overlap")  else 200
    val batch  = if (cfg.hasPath("embed.batch"))    cfg.getInt("embed.batch")    else 8

    // Single source of truth for MR output root (must NOT exist before run)
    val outMr  = s"${outDir}_mr_out"

    log.info(s"JobMain: input=$input  output=$outMr  shards=$shards  model=$model  (maxChars=$maxCh, overlap=$ovl, batch=$batch)")

    // ---- Hadoop config (local | yarn) ----
    val mode =
      if (args.contains("--mode")) args(args.indexOf("--mode") + 1)
      else if (cfg.hasPath("mr.mode")) cfg.getString("mr.mode")
      else "yarn" // default to "real Hadoop/EMR"

    log.info("JobMain: execution mode={}", mode)

    val hConf = new Configuration() // loads core-site.xml/hdfs-site.xml/yarn-site.xml if present

    def setLocal(): Unit = {
      hConf.set("fs.defaultFS", "file:///")
      hConf.set("mapreduce.framework.name", "local")
    }

    mode match {
      case "local" => setLocal()
      case "yarn"  =>
        val hasYarn =
          hConf.getResource("yarn-site.xml") != null ||
            sys.env.get("YARN_CONF_DIR").nonEmpty ||
            sys.env.get("HADOOP_CONF_DIR").nonEmpty
        if (!hasYarn)
          throw new IllegalStateException(
            "YARN configs not found. Run with --mode local for dev, or submit on a cluster."
          )
      case other => throw new IllegalArgumentException(s"Unknown --mode: $other")
    }

    // Helpful: print exactly what you’re using
    import scala.util.Try
    Try(log.info(s"framework=${hConf.get("mapreduce.framework.name")}"))
    Try(log.info(s"defaultFS=${hConf.get("fs.defaultFS")}"))

    // ---- propagate knobs to tasks ----
    hConf.setInt("mapreduce.job.reduces", shards)
    hConf.set("msr.embed.model", model)
    hConf.setInt("msr.embed.batch", batch)
    hConf.setInt("msr.chunk.maxChars", maxCh)
    hConf.setInt("msr.chunk.overlap", ovl)

    // Reducer copies Lucene shards under this root
    hConf.set("msr.output.dir", outMr)

    // Optional: pass Ollama host (useful when mappers must hit an EC2 IP/DNS)
    sys.env.get("OLLAMA_HOST").foreach { h =>
      log.info("JobMain: propagating OLLAMA_HOST={}", h)
      hConf.set("msr.ollama.host", h)
    }

    // ---- Build job ----
    log.info("JobMain: constructing Hadoop Job")
    val job = Job.getInstance(hConf, s"MSR-RAG Index (shards=$shards)")
//    job.setJarByClass(classOf[RagMapper]) // ensures all classes are on the job jar

    // Mapper/Reducer classes
//    job.setMapperClass(classOf[RagMapper])
//    job.setReducerClass(classOf[ShardReducer])
    job.setNumReduceTasks(shards)

    // Mapper outputs
    job.setMapOutputKeyClass(classOf[IntWritable])
    job.setMapOutputValueClass(classOf[Text])

    // Final outputs (from reducer)
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

    // IO formats + paths
    job.setInputFormatClass(classOf[TextInputFormat])
    FileInputFormat.addInputPath(job, new Path(input)) // text file with absolute PDF URIs/paths

    job.setOutputFormatClass(classOf[TextOutputFormat[Text, Text]])
    FileOutputFormat.setOutputPath(job, new Path(outMr)) // MUST NOT exist before run

    log.info("JobMain: submitting job …")
    val ok = job.waitForCompletion(true)
    log.info("JobMain: job finished with status ok={}", Boolean.box(ok))
    if (!ok) sys.exit(1)
  }
}
