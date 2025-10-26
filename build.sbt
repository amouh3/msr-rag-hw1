// build.sbt

ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "edu.uic"
ThisBuild / fork := true

lazy val root = (project in file("."))
  .settings(
    name := "msr-rag-hw2",

    // ---- Default entry point for `sbt run` ----
    Compile / mainClass := Some("edu.uic.msr.rag.DeltaIndexer"),
    Compile / run / javaOptions += "-Dconfig.file=conf/spark.local.conf",

    // ---- Dependencies ----
    libraryDependencies ++= Seq(
      // Config + logging
      "com.typesafe" % "config" % "1.4.3",
      "org.slf4j"      %  "slf4j-api"       % "1.7.36",
      "ch.qos.logback" %  "logback-classic" % "1.2.13",

      // Lucene + commons-io
      "org.apache.lucene" % "lucene-core"            % "9.10.0",
      "org.apache.lucene" % "lucene-analysis-common" % "9.10.0",
      "org.apache.lucene" % "lucene-queryparser"     % "9.10.0",
      "commons-io"        % "commons-io"             % "2.11.0",

      // Spark + Delta
      "org.apache.spark" %% "spark-sql"     % "3.5.1",
      "io.delta"         %% "delta-spark"   % "3.2.0",

      // Hadoop bits (optional locally)
      "org.apache.hadoop" % "hadoop-aws"    % "3.3.6",
      "org.apache.hadoop" % "hadoop-common" % "3.3.6",

      // PDF parsing (from HW1)
      "org.apache.pdfbox" % "pdfbox" % "2.0.31",

      // HTTP client + JSON (Ollama API)
      "com.softwaremill.sttp.client3" %% "core"   % "3.9.5",
      "com.softwaremill.sttp.client3" %% "circe"  % "3.9.5",
      "io.circe"                      %% "circe-core"    % "0.14.10",
      "io.circe"                      %% "circe-parser"  % "0.14.10",
      "io.circe"                      %% "circe-generic" % "0.14.10",

      // Tests
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),

    // Pin SLF4J to avoid overload weirdness
    dependencyOverrides ++= Seq(
      "org.slf4j" % "slf4j-api" % "1.7.36"
    )
  )
