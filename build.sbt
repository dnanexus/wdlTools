import sbt.Keys._
import sbtassembly.AssemblyPlugin.autoImport._

scalaVersion := "2.12.9"
name := "wdlTools"
organization := "com.dnanexus"
val root = Project("root", file("."))

// reduce the maximum number of errors shown by the Scala compiler
maxErrors := 7

//coverageEnabled := true

javacOptions ++= Seq("-Xlint:deprecation")

// Show deprecation warnings
scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-explaintypes",
    "-encoding",
    "UTF-8",
    "-Xfuture",
    "-Xlint:by-name-right-associative",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
//    "-Xlint:missing-interpolator",
    "-Xlint:nullary-override",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Ypartial-unification", // https://typelevel.org/cats
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:privates",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:imports", // warns about every unused import on every command.
    "-Xfatal-warnings" // makes those warnings fatal.
)

assemblyJarName in assembly := "wdlTools.jar"
logLevel in assembly := Level.Info
//assemblyOutputPath in assembly := file("applet_resources/resources/dxWDL.jar")

//antlr4Version in Antlr4 := "4.8.0"
//antlr4PackageName in Antlr4 := Some("org.openwdl.wdl.parser")
//antlr4GenVisitor in Antlr4 := true
//antlr4TreatWarningsAsErrors in Antlr4 := true

val antlr4Version = "4.8"
val scallopVersion = "3.4.0"
val typesafeVersion = "1.3.3"
val scalateVersion = "1.9.5"

libraryDependencies ++= Seq(
    // antlr4 lexer + parser
    "org.antlr" % "antlr4" % antlr4Version,
    // command line parser
    "org.rogach" %% "scallop" % scallopVersion,
    // template engine
    "org.scalatra.scalate" %% "scalate-core" % scalateVersion,
    "com.typesafe" % "config" % typesafeVersion,
    //---------- Test libraries -------------------//
    "org.scalactic" %% "scalactic" % "3.0.1",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)

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
Test / testOptions += Tests.Argument("-oF")

Test / parallelExecution := false

// comment out this line to enable tests in assembly
test in assembly := {}

// scalafmt
scalafmtConfig := root.base / ".scalafmt.conf"
// Coverage
//
// sbt clean coverage test
// sbt coverageReport

// To turn it off do:
// sbt coverageOff

// Ignore code parts that cannot be checked in the unit
// test environment
//coverageExcludedPackages := "dxWDL.Main;dxWDL.compiler.DxNI;dxWDL.compiler.DxObjectDirectory;dxWDL.compiler.Native"
