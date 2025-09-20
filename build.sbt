ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "edu.uic"

lazy val root = (project in file("."))
  .settings(
    name := "msr-rag-hw1",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "com.typesafe"  %  "config"    % "1.4.3",
      "ch.qos.logback"%  "logback-classic" % "1.5.6",
      "org.apache.pdfbox" % "pdfbox" % "2.0.31",

      // Hadoop MapReduce (compile on Windows; we’ll run locally later / on EMR)
      "org.apache.hadoop" % "hadoop-common" % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-core" % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % "3.3.6",

      // HTTP client + JSON (Ollama API)
      "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
      "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
      "io.circe" %% "circe-generic" % "0.14.9",
      "io.circe" %% "circe-parser"  % "0.14.9",

      // Lucene index + analysis + vectors (HNSW)
      "org.apache.lucene" % "lucene-core" % "9.10.0",
      "org.apache.lucene" % "lucene-analysis-common" % "9.10.0",
      "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
      "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
      "io.circe" %% "circe-generic" % "0.14.9",
      "io.circe" %% "circe-parser"  % "0.14.9",

    )

  )
