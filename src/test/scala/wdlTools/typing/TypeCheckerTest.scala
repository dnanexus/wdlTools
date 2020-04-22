package wdlTools.typing

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.Parsers
import wdlTools.util.{Options, SourceCode, TypeCheckingRegime, Util, Verbosity}

class TypeCheckerTest extends FlatSpec with Matchers {
  private val opts = Options(
      antlr4Trace = false,
      localDirectories = Some(
          Vector(
              Paths.get(getClass.getResource("/typing/v1").getPath)
          )
      ),
      verbosity = Verbosity.Quiet,
      followImports = true
  )
  private val loader = SourceCode.Loader(opts)
  private val parser = Parsers(opts, Some(loader))

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(folder: Path): Vector[Path] = {
    Files.exists(folder) shouldBe true
    Files.isDirectory(folder) shouldBe true
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".wdl"))
  }

  // Expected results for a test, and any additional flags required
  // to run it.
  case class TResult(correct: Boolean, flags: Option[TypeCheckingRegime.Value] = None)

  val controlTable: Map[String, TResult] = Map(
      // workflows
      "census.wdl" -> TResult(true),
      "compound_expr_bug.wdl" -> TResult(true),
      "imports.wdl" -> TResult(true),
      "linear.wdl" -> TResult(true),
      "nested.wdl" -> TResult(true),
      "types.wdl" -> TResult(true),
      "coercions_questionable.wdl" -> TResult(true, Some(TypeCheckingRegime.Lenient)),
      "coercions_strict.wdl" -> TResult(true),
      "bad_stdlib_calls.wdl" -> TResult(false),
      "scatter_II.wdl" -> TResult(false),
      "scatter_I.wdl" -> TResult(false),
      "shadow_II.wdl" -> TResult(false),
      "shadow.wdl" -> TResult(false),
      // correct tasks
      "command_string.wdl" -> TResult(true),
      "comparisons.wdl" -> TResult(true),
      "library.wdl" -> TResult(true),
      "simple.wdl" -> TResult(true),
      "stdlib.wdl" -> TResult(true),
      // incorrect tasks
      "comparison1.wdl" -> TResult(false),
      "comparison2.wdl" -> TResult(false),
      "comparison4.wdl" -> TResult(false),
      "declaration_shadowing.wdl" -> TResult(false),
      "simple.wdl" -> TResult(false),
      // expressions
      "expressions.wdl" -> TResult(true),
      "expressions_bad.wdl" -> TResult(false),

    // metadata
    "metadata_null_value.wdl" -> TResult(true),
    "metadata_complex.wdl" -> TResult(true)
  )

  // test to include/exclude
  private val includeList: Option[Set[String]] = None // Some(Set("metadata_null_value.wdl", "metadata_complex.wdl"))
  private val excludeList: Option[Set[String]] = None

  private def checkCorrect(file: Path, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val opts2 = flag match {
      case None    => opts
      case Some(x) => opts.copy(typeChecking = x)
    }
    val stdlib = Stdlib(opts2)
    val checker = TypeChecker(stdlib)
    try {
      val doc = parser.parse(Util.getURL(file))
      checker.apply(doc)
    } catch {
      case e: Throwable =>
        Util.error(s"Type error in file ${file.toString}")
        throw e
    }
  }

  private def checkIncorrect(file: Path, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val opts2 = flag match {
      case None    => opts
      case Some(x) => opts.copy(typeChecking = x)
    }
    val stdlib = Stdlib(opts2)
    val checker = TypeChecker(stdlib)
    val checkVal =
      try {
        val doc = parser.parse(Util.getURL(file))
        checker.apply(doc)
        true
      } catch {
        case _: Throwable =>
          // This file should NOT pass type validation.
          // The exception is expected at this point.
          false
      }
    if (checkVal) {
      throw new RuntimeException(s"Type error missed in file ${file.toString}")
    }
  }

  private def includeExcludeCheck(name: String): Boolean = {
    excludeList match {
      case Some(l) if (l contains name) => return false
      case _                            => ()
    }
    includeList match {
      case None                         => return true
      case Some(l) if (l contains name) => return true
      case Some(_)                      => return false
    }
  }

  it should "type check test wdl files" taggedAs (Edge) in {
    val testFiles = getWdlSourceFiles(
        Paths.get(getClass.getResource("/typing/v1").getPath)
    )

    // filter out files that do not appear in the control table
    val testFiles2 = testFiles.flatMap {
      case testFile =>
        val name = testFile.getFileName().toString
        if (!includeExcludeCheck(name)) {
          None
        } else {
          controlTable.get(name) match {
            case None => None
            case Some(tResult) =>
              Some(testFile, tResult)
          }
        }
    }

    testFiles2.foreach {
      case (testFile, TResult(true, flag)) =>
        checkCorrect(testFile, flag)
      case (testFile, TResult(false, flag)) =>
        checkIncorrect(testFile, flag)
    }
  }

  it should "be able to handle GATK" in {
    val opts2 = opts.copy(typeChecking = TypeCheckingRegime.Lenient)
    val stdlib = Stdlib(opts2)
    val checker = TypeChecker(stdlib)

    val sources = Vector(
        "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping-terra.wdl",
        "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping.wdl",
        "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/haplotypecaller-gvcf-gatk4.wdl",
        // Uses the keyword "version "
        //"https://raw.githubusercontent.com/gatk-workflows/gatk4-data-processing/master/processing-for-variant-discovery-gatk4.wdl"
        "https://raw.githubusercontent.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/master/JointGenotypingWf.wdl"
        // Non standard usage of place holders
        //"https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl"
        //
        // https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl#L1208
        //  Array[String]? ignore
        //  String s2 = {default="null" sep=" IGNORE=" ignore}
        //
        //  # syntax error in place-holder
        //  # https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl#L1210
        //  Boolean? is_outlier_data
        //  String s3 = ${default='SKIP_MATE_VALIDATION=false' true='SKIP_MATE_VALIDATION=true' false='SKIP_MATE_VALIDATION=false' is_outlier_data}
        //
    )

    for (src <- sources) {
      val url = Util.getURL(src)
      val doc = parser.parse(url)
      checker.apply(doc)
    }
  }
}
