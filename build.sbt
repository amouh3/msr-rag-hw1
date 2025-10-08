// build.sbt

import sbtassembly.MergeStrategy
import sbtassembly.PathList

ThisBuild / scalaVersion  := "3.5.1"
ThisBuild / organization  := "edu.uic"
Compile / mainClass := Some("edu.uic.msr.RunAll")
ThisBuild / fork := true

lazy val root = (project in file("."))
  .settings(
    name := "msr-rag-hw1",

    libraryDependencies ++= Seq(
      // Config + logging
      "com.typesafe"   %  "config"          % "1.4.3",
      "org.slf4j"      %  "slf4j-api"       % "2.0.13",
      "ch.qos.logback" %  "logback-classic" % "1.5.6",

      // Hadoop + S3
      "org.apache.hadoop" % "hadoop-aws"                       % "3.3.6",
      "org.apache.hadoop" % "hadoop-common"                    % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-core"     % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient"% "3.3.6",

      // PDF parsing
      "org.apache.pdfbox" % "pdfbox" % "2.0.31",

      // HTTP client + JSON (Ollama API)
      "com.softwaremill.sttp.client3" %% "core"   % "3.9.5",
      "com.softwaremill.sttp.client3" %% "circe"  % "3.9.5",
      "io.circe"                      %% "circe-core"    % "0.14.10",
      "io.circe"                      %% "circe-parser"  % "0.14.10",
      "io.circe"                      %% "circe-generic" % "0.14.10",

      // Lucene
      "org.apache.lucene" % "lucene-core"            % "9.10.0",
      "org.apache.lucene" % "lucene-analysis-common" % "9.10.0",
      "org.apache.lucene" % "lucene-codecs"          % "9.10.0",

      // Tests
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),

    // ---- app entry point ----
    Compile / mainClass := Some("edu.uic.msr.rag.JobMain"),

    // ---- sbt-assembly (fat JAR) ----
    assembly / mainClass     := Some("edu.uic.msr.rag.JobMain"),
    assembly / assemblyJarName:= "msr-rag-hw1-assembly.jar",

    // Single, robust merge strategy:
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _ @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) if xs.nonEmpty => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case p if p.endsWith("module-info.class") => MergeStrategy.discard
      // handle multi-release JAR services
      case p if p.startsWith("META-INF/versions/") && p.contains("/services/") => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
