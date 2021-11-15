import sbt.Keys._
import sbt.ThisBuild
import sbtassembly.AssemblyPlugin.autoImport._
import com.typesafe.config._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtConfig
import sbtghpackages.GitHubPackagesPlugin.autoImport.githubOwner

import Merging.customMergeStrategy

def getVersion: String = {
  val confPath =
    Option(System.getProperty("config.file")).getOrElse("src/main/resources/application.conf")
  val conf = ConfigFactory.parseFile(new File(confPath)).resolve()
  conf.getString("wdlTools.version")
}

name := "wdlTools"

ThisBuild / organization := "com.dnanexus"
ThisBuild / scalaVersion := "2.13.2"
ThisBuild / developers := List(
    Developer("orodeh", "orodeh", "orodeh@dnanexus.com", url("https://github.com/dnanexus")),
    Developer("jdidion", "jdidion", "jdidion@dnanexus.com", url("https://github.com/dnanexus")),
    Developer("r-i-v-a",
              "Riva Nathans",
              "rnathans-cf@dnanexus.com",
              url("https://github.com/dnanexus"))
)
ThisBuild / homepage := Some(url("https://github.com/dnanexus/wdlTools"))
ThisBuild / scmInfo := Some(
    ScmInfo(url("https://github.com/dnanexus/wdlTools"), "git@github.com:dnanexus/wdlTools.git")
)
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

lazy val root = project.in(file("."))
lazy val wdlTools = root.settings(
    name := "wdlTools",
    version := getVersion,
    settings,
    assemblySettings,
    libraryDependencies ++= dependencies,
    assemblyJarName in assembly := "wdlTools.jar"
)

lazy val dependencies = {
  val dxCommonVersion = "0.8.1-SNAPSHOT"
  val antlr4Version = "4.9.2"
  val scallopVersion = "3.4.0"
  val typesafeVersion = "1.4.1"
  val scalateVersion = "1.9.7"
  //val beardVersion = "0.3.1"
  val sprayVersion = "1.3.6"
  val katanVersion = "0.6.1"
  val re2jVersion = "1.6"
  val graphVersion = "1.13.2"
  val scalatestVersion = "3.2.9"

  Seq(
      "com.dnanexus" % "dxcommon" % dxCommonVersion,
      // configuration
      "com.typesafe" % "config" % typesafeVersion,
      // antlr4 lexer + parser
      "org.antlr" % "antlr4" % antlr4Version,
      // command line parser
      "org.rogach" %% "scallop" % scallopVersion,
      // template engine
      "org.scalatra.scalate" %% "scalate-core" % scalateVersion,
      // TODO: switch to "de.zalando" %% "beard" % beardVersion,
      "io.spray" %% "spray-json" % sprayVersion,
      // csv parser
      "com.nrinaudo" %% "kantan.csv" % katanVersion,
      // POSIX regexp library
      "com.google.re2j" % "re2j" % re2jVersion,
      // graph library
      "org.scala-graph" %% "graph-core" % graphVersion,
      //---------- Test libraries -------------------//
      "org.scalatest" % "scalatest_2.13" % scalatestVersion % "test"
  )
}

//resolvers ++= Seq(
//    "zalando-maven" at "https://dl.bintray.com/zalando/maven"
//)

val githubDxScalaResolver = Resolver.githubPackages("dnanexus", "dxScala")
val githubWdlToolsResolver = Resolver.githubPackages("dnanexus", "wdlTools")
resolvers ++= Vector(githubWdlToolsResolver, githubDxScalaResolver)

val releaseTarget = Option(System.getProperty("releaseTarget")).getOrElse("github")

lazy val settings = Seq(
    scalacOptions ++= compilerOptions,
    // exclude Java sources from scaladoc
    scalacOptions in (Compile, doc) ++= Seq("-no-java-comments", "-no-link-warnings"),
    javacOptions ++= Seq("-Xlint:deprecation"),
    // reduce the maximum number of errors shown by the Scala compiler
    maxErrors := 20,
    // scalafmt
    scalafmtConfig := root.base / ".scalafmt.conf",
    // disable publish with scala version, otherwise artifact name will include scala version
    // e.g wdlTools_2.11
    crossPaths := false,
    // add sonatype repository settings
    // snapshot versions publish to sonatype snapshot repository
    // other versions publish to sonatype staging repository
    publishTo := Some(
        if (isSnapshot.value || releaseTarget == "github") {
          githubWdlToolsResolver
        } else {
          Opts.resolver.sonatypeStaging
        }
    ),
    githubOwner := "dnanexus",
    githubRepository := "wdlTools",
    publishMavenStyle := true,
    // If an exception is thrown during tests, show the full
    // stack trace, by adding the "-oF" option to the list.
    //
    // exclude the native tests, they are slow.
    // to do this from the command line:
    // sbt testOnly -- -l native
    //
    // comment out this line in order to allow native
    // tests
    // Test / testOptions += Tests.Argument("-l", "native")
    Test / testOptions += Tests.Argument("-oF"),
    Test / parallelExecution := false

    // Coverage
    //
    // sbt clean coverage test
    // sbt coverageReport
    // To turn it off do:
    // sbt coverageOff
    //coverageEnabled := true
    // Ignore code parts that cannot be checked in the unit
    // test environment
    //coverageExcludedPackages := "dxWDL.Main;dxWDL.compiler.DxNI;dxWDL.compiler.DxObjectDirectory;dxWDL.compiler.Native"
)

val compilerOptions = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-explaintypes",
    "-encoding",
    "UTF-8",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:nullary-override",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Ywarn-dead-code",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:privates",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:imports", // warns about every unused import on every command.
    "-Xfatal-warnings" // makes those warnings fatal.
)

// Assembly
lazy val assemblySettings = Seq(
    logLevel in assembly := Level.Info,
    // comment out this line to enable tests in assembly
    test in assembly := {},
    assemblyMergeStrategy in assembly := customMergeStrategy.value
)
