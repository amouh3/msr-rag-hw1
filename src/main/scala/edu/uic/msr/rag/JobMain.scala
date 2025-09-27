package edu.uic.msr.rag

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, TextOutputFormat}
import org.slf4j.LoggerFactory

/** Runs the MR job:
 *   input: mr.inputList  (text file with absolute PDF paths, one per line)
 *   output: mr.outputDir (root dir; reducers write shard outputs under it)
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

    val input  = cfg.getString("mr.inputList")   // e.g. file:///C:/tmp/pdf_list.txt
    val outDir = cfg.getString("mr.outputDir")   // e.g. file:///C:/tmp/index_shards
    val shards = cfg.getInt("mr.shards")
    val model  = cfg.getString("embed.model")
    val maxCh  = if (cfg.hasPath("embed.maxChars")) cfg.getInt("embed.maxChars") else 1000
    val ovl    = if (cfg.hasPath("embed.overlap"))  cfg.getInt("embed.overlap")  else 200
    val batch  = if (cfg.hasPath("embed.batch")) cfg.getInt("embed.batch") else 8
    log.info(s"JobMain: input=$input  output=$outDir  shards=$shards  model=$model")

    // Hadoop configuration in LOCAL mode
    val hConf = new Configuration()
    hConf.set("fs.defaultFS", "file:///")
    hConf.set("mapreduce.framework.name", "local")
    hConf.set("mapreduce.jobtracker.address", "local")
    hConf.setInt("msr.embed.batch", batch)

    // Let mappers/reducers see these settings
    hConf.setInt("mapreduce.job.reduces", shards)
    hConf.set("msr.embed.model", model)
    hConf.set("msr.output.dir", outDir)
    hConf.setInt("msr.chunk.maxChars", maxCh)
    hConf.setInt("msr.chunk.overlap", ovl)
    sys.env.get("OLLAMA_HOST").foreach(h => hConf.set("msr.ollama.host", h))

    val job = Job.getInstance(hConf, s"MSR-RAG Index (shards=$shards)")
    job.setJarByClass(classOf[RagMapper])

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
    FileInputFormat.addInputPath(job, new Path(input))

    job.setOutputFormatClass(classOf[TextOutputFormat[Text, Text]])
    // Hadoop requires the output dir NOT to exist
    FileOutputFormat.setOutputPath(job, new Path(outDir + "_mr_out"))

    val ok = job.waitForCompletion(true)
    if (!ok) sys.exit(1)
  }
}
