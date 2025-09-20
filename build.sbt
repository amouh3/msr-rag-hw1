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
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % "3.3.6"
    )

  )
