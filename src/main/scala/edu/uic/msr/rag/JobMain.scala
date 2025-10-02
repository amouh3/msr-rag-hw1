package edu.uic.msr.rag

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, TextOutputFormat}
import org.slf4j.LoggerFactory

/** Runs the Map/Reduce job in LOCAL mode.
 *
 *  Config keys (conf/local.conf):
 *    mr.inputList = "file:///C:/tmp/pdf_list.txt"   // absolute PDF paths, one per line
 *    mr.outputDir = "file:///C:/tmp/index_shards"   // logical root (we'll write to <outputDir>_mr_out)
 *    mr.shards    = 2                                // number of reducers / Lucene shards
 *    embed.model  = "mxbai-embed-large"
 *    embed.maxChars (optional, default 1000)
 *    embed.overlap (optional, default 200)
 *    embed.batch   (optional, default 8)
 *
 * Usage:
 *   sbt "runMain edu.uic.msr.rag.JobMain --conf conf/local.conf"
 */
object JobMain {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val confPath =
      if (args.contains("--conf")) args(args.indexOf("--conf") + 1) else "conf/local.conf"

    val cfg = ConfigFactory.parseFile(new java.io.File(confPath)).resolve()

    // ---- read config ----
    val input  = cfg.getString("mr.inputList")   // e.g. file:///C:/tmp/pdf_list.txt
    val outDir = cfg.getString("mr.outputDir")   // e.g. file:///C:/tmp/index_shards
    val shards = cfg.getInt("mr.shards")
    val model  = cfg.getString("embed.model")
    val maxCh  = if (cfg.hasPath("embed.maxChars")) cfg.getInt("embed.maxChars") else 1000
    val ovl    = if (cfg.hasPath("embed.overlap"))  cfg.getInt("embed.overlap")  else 200
    val batch  = if (cfg.hasPath("embed.batch"))    cfg.getInt("embed.batch")    else 8

    // Single source of truth for MR output root (must NOT exist before run)
    val outMr  = s"${outDir}_mr_out"

    log.info(s"JobMain: input=$input  output=$outMr  shards=$shards  model=$model")

    // ---- Hadoop config (local | yarn) ----
    val mode =
      if (args.contains("--mode")) args(args.indexOf("--mode") + 1)
      else if (cfg.hasPath("mr.mode")) cfg.getString("mr.mode")
      else "yarn" // default to "real Hadoop"

    val hConf = new Configuration() // loads core/hdfs/yarn-site.xml if present

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
          throw new IllegalStateException("YARN configs not found. Run with --mode local for dev, or submit on a cluster.")
      case other => throw new IllegalArgumentException(s"Unknown --mode: $other")
    }

    // Helpful: print exactly what you’re using
    import scala.util.Try
    Try(log.info(s"framework=${hConf.get("mapreduce.framework.name")}"))
    Try(log.info(s"defaultFS=${hConf.get("fs.defaultFS")}"))


    // Pass knobs to Mapper/Reducer via job conf
    hConf.setInt("mapreduce.job.reduces", shards)
    hConf.set("msr.embed.model", model)
    hConf.setInt("msr.embed.batch", batch)
    hConf.setInt("msr.chunk.maxChars", maxCh)
    hConf.setInt("msr.chunk.overlap", ovl)

    // IMPORTANT: Reducer copies Lucene shards under this root
    hConf.set("msr.output.dir", outMr)

    // Optional: propagate Ollama host if you want to read it inside tasks
    sys.env.get("OLLAMA_HOST").foreach(h => hConf.set("msr.ollama.host", h))

    // ---- Build job ----
    val job = Job.getInstance(hConf, s"MSR-RAG Index (shards=$shards)")
    job.setJarByClass(classOf[RagMapper])                 // ensures all classes are on the job jar

    // Mapper/Reducer classes
    job.setMapperClass(classOf[RagMapper])
    job.setReducerClass(classOf[ShardReducer])
    job.setNumReduceTasks(shards)

    // Mapper outputs
    job.setMapOutputKeyClass(classOf[IntWritable])
    job.setMapOutputValueClass(classOf[Text])

    // Final outputs (from reducer)
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

    // IO formats + paths
    job.setInputFormatClass(classOf[TextInputFormat])
    FileInputFormat.addInputPath(job, new Path(input))     // the text file with absolute PDF paths

    job.setOutputFormatClass(classOf[TextOutputFormat[Text, Text]])
    FileOutputFormat.setOutputPath(job, new Path(outMr))   // MUST NOT exist before run

    val ok = job.waitForCompletion(true)
    if (!ok) sys.exit(1)
  }
}
