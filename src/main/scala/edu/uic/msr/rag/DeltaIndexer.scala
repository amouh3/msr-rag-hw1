package edu.uic.msr.rag

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import edu.uic.msr.pdf.PdfText
import io.delta.tables._

object DeltaIndexer {
  def main(args: Array[String]): Unit = {
    // 1) Config
    val app = DeltaUtils.loadConf()

    val spark = SparkSession.builder()
      .appName("DeltaIndexer")
      .master(app.sparkMaster)
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()

    spark.sparkContext.setLogLevel("INFO")
    import spark.implicits._

    val rawDir    = app.paths.rawDir
    val deltaRoot = app.paths.deltaRoot
    val docsName  = app.tables.docs              // e.g. "docs" (plain name, no slashes)

    println(
      s"""
         |======== DeltaIndexer ========
         | spark.master      = ${app.sparkMaster}
         | paths.rawDir      = $rawDir
         | paths.deltaRoot   = $deltaRoot
         | tables.docs       = $docsName
         |================================
         |""".stripMargin)

    // Resolve the filesystem path we will write/read
    new java.io.File(deltaRoot).mkdirs()
    val docsPath = s"$deltaRoot/$docsName"       // e.g. var/delta/docs

    // 2) Read files as binary
    val raw = spark.read.format("binaryFile")
      .option("recursiveFileLookup", "true")
      .load(rawDir)

    // 3) UDFs / helpers
    val normalizeUdf       = udf((bytes: Array[Byte], path: String) => {
      val text =
        if (path.toLowerCase.endsWith(".pdf")) PdfText.extract(bytes)
        else new String(bytes, "UTF-8")
      DeltaUtils.normalizeWhitespace(text)
    })
    val detectLangUdf      = udf(DeltaUtils.detectLang _)
    val firstLineOrNameUdf = udf(DeltaUtils.firstLineOrName _)
    val sha256Udf          = udf(DeltaUtils.sha256Hex _)

    // 4) Build docs DF
    val docs = raw.select(
        col("path").as("uri"),
        normalizeUdf(col("content"), col("path")).as("text")
      ).withColumn("language", detectLangUdf(col("text")))
      .withColumn("title", firstLineOrNameUdf(col("text"), col("uri")))
      .withColumn("docId", sha256Udf(col("uri")))
      .withColumn("contentHash", sha256Udf(col("text")))

    // 5) Write/Merge Delta (PATH-BASED, no catalog table)
    val docsExists =
      try { spark.read.format("delta").load(docsPath); true }
      catch { case _: Throwable => false }


    if (!docsExists) {
      docs.write.format("delta").mode("overwrite").save(docsPath)
    } else {
      val existing = DeltaTable.forPath(spark, docsPath)

      // Upsert (MERGE) using the Delta API, not raw SQL
      existing.as("t")
        .merge(
          docs.as("s"),
          "t.docId = s.docId AND t.contentHash = s.contentHash"
        )
        .whenNotMatched()
        .insertAll()
        .execute()
    }

    // 6) Read back by PATH and count
    val total = spark.read.format("delta").load(docsPath).count()
    println(s"[DeltaIndexer] docs at '$docsPath' (rows=$total)")

    spark.stop()
    println("======== DeltaIndexer done ========")
  }
}
