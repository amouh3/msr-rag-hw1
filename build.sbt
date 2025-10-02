// build.sbt
ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "edu.uic"

lazy val root = (project in file("."))
  .settings(
    name := "msr-rag-hw1",

    // ---- dependencies (deduped) ----
    libraryDependencies ++= Seq(
      // Config + logging
      "com.typesafe"   %  "config"          % "1.4.3",
      "org.slf4j"      %  "slf4j-api"       % "2.0.13",
      "ch.qos.logback" %  "logback-classic" % "1.5.6",

      // Hadoop MapReduce client (ok to keep in the fat JAR for class projects)
      "org.apache.hadoop" % "hadoop-common"                   % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-core"    % "3.3.6",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient"% "3.3.6",

      // PDF parsing
      "org.apache.pdfbox" % "pdfbox" % "2.0.31",

      // HTTP client + JSON (Ollama API)
      "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
      "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
      "io.circe"                      %% "circe-core"    % "0.14.10",
      "io.circe"                      %% "circe-parser"  % "0.14.10",
      "io.circe"                      %% "circe-generic" % "0.14.10",

      // Lucene
      "org.apache.lucene" % "lucene-core"             % "9.10.0",
      "org.apache.lucene" % "lucene-analysis-common"  % "9.10.0",

      // Tests
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),

    // ---- sbt-assembly settings ----
    assembly / mainClass := Some("edu.uic.msr.rag.JobMain"),
    assembly / assemblyJarName := "msr-rag-hw1-assembly.jar",

    // Merge strategy to avoid META-INF and reference.conf collisions
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs.map(_.toLowerCase) match {
          case "manifest.mf" :: Nil            => MergeStrategy.discard
          case "index.list" :: Nil             => MergeStrategy.discard
          case "dependencies" :: Nil           => MergeStrategy.discard
          case x if x.exists(_.endsWith(".sf"))=> MergeStrategy.discard
          case _                               => MergeStrategy.first
        }
      case PathList("module-info.class")         => MergeStrategy.discard
      case PathList("reference.conf")            => MergeStrategy.concat
      case PathList("application.conf")          => MergeStrategy.concat
      case PathList("META-INF", "services", _ @ _*) => MergeStrategy.concat
      case _                                     => MergeStrategy.first
    }
  )
