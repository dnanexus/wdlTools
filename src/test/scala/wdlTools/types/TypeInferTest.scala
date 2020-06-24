package wdlTools.types

import java.nio.file.{Files, Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._
import wdlTools.syntax.Parsers
import wdlTools.util.{Verbosity, Util => UUtil}

class TypeInferTest extends AnyFlatSpec with Matchers {
  private val opts = TypeOptions(
      antlr4Trace = false,
      localDirectories = Vector(
          Paths.get(getClass.getResource("/types/v1").getPath)
      ),
      verbosity = Verbosity.Quiet,
      followImports = true
  )
  private val parser = Parsers(opts)

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
      "census.wdl" -> TResult(correct = true),
      "compound_expr_bug.wdl" -> TResult(correct = true),
      "imports.wdl" -> TResult(correct = true),
      "linear.wdl" -> TResult(correct = true),
      "nested.wdl" -> TResult(correct = true),
      "coercions_questionable.wdl" -> TResult(correct = true, Some(TypeCheckingRegime.Lenient)),
      "coercions_strict.wdl" -> TResult(correct = true),
      "bad_stdlib_calls.wdl" -> TResult(correct = false),
      "scatter_II.wdl" -> TResult(correct = false),
      "scatter_I.wdl" -> TResult(correct = false),
      "shadow_II.wdl" -> TResult(correct = true),
      "shadow.wdl" -> TResult(correct = false),
      "polymorphic_types.wdl" -> TResult(correct = true),
      "empty_array_in_call.wdl" -> TResult(correct = true, Some(TypeCheckingRegime.Strict)),
      // missing arguments that may be supplied with a companion json inputs file
      "missing_args.wdl" -> TResult(correct = true),
      // correct tasks
      "command_string.wdl" -> TResult(correct = true),
      "comparisons.wdl" -> TResult(correct = true),
      "library.wdl" -> TResult(correct = true),
      "simple.wdl" -> TResult(correct = true),
      "stdlib.wdl" -> TResult(correct = true),
      "input_section.wdl" -> TResult(correct = true),
      "output_section.wdl" -> TResult(correct = true),
      "echo-pairs.wdl" -> TResult(correct = true),
      // incorrect tasks
      "comparison1.wdl" -> TResult(correct = false),
      "comparison2.wdl" -> TResult(correct = false),
      "comparison4.wdl" -> TResult(correct = false),
      "declaration_shadowing.wdl" -> TResult(correct = false),
      "simple.wdl" -> TResult(correct = false),
      // expressions
      "expressions.wdl" -> TResult(correct = true),
      "expressions_bad.wdl" -> TResult(correct = false),
      // metadata
      "meta_null_value.wdl" -> TResult(correct = true),
      "meta_section_compound.wdl" -> TResult(correct = true),
      "invalid_param_meta.wdl" -> TResult(correct = false),
      // runtime section
      "runtime_section_I.wdl" -> TResult(correct = true),
      "runtime_section_bad.wdl" -> TResult(correct = false)
  )

  // test to include/exclude
  private val includeList: Option[Set[String]] = None
  private val excludeList: Option[Set[String]] = None

  private def checkCorrect(file: Path, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val opts2 = flag match {
      case None    => opts
      case Some(x) => opts.copy(typeChecking = x)
    }
    val checker = TypeInfer(opts2)
    try {
      val doc = parser.parseDocument(UUtil.pathToUrl(file))
      checker.apply(doc)
    } catch {
      case e: Throwable =>
        UUtil.error(s"Type error in file ${file.toString}")
        throw e
    }
  }

  private def checkIncorrect(file: Path, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val opts2 = flag match {
      case None    => opts
      case Some(x) => opts.copy(typeChecking = x)
    }
    val checker = TypeInfer(opts2)
    val checkVal =
      try {
        val doc = parser.parseDocument(UUtil.pathToUrl(file))
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
      case Some(l) if l contains name => return false
      case _                          => ()
    }
    includeList match {
      case None                       => true
      case Some(l) if l contains name => true
      case Some(_)                    => false
    }
  }

  it should "type check test wdl files" in {
    val testFiles = getWdlSourceFiles(
        Paths.get(getClass.getResource("/types/v1").getPath)
    )

    // check that all results have a corresponding file
    val fileNames = testFiles.map(_.getFileName.toString).toSet
    val controlNames = controlTable.keys.toSet
    val diff1 = fileNames.diff(controlNames)
    if (diff1.nonEmpty)
      throw new Exception(s"files ${diff1} do not have a matching solution")
    val diff2 = controlNames.diff(fileNames)
    if (diff2.nonEmpty)
      throw new Exception(s"solutions ${diff2} do not have a matching test file")

    // filter out files that do not appear in the control table
    val testFiles2 = testFiles.flatMap { testFile =>
      val name = testFile.getFileName.toString
      if (!includeExcludeCheck(name)) {
        None
      } else {
        val tResult = controlTable(name)
        Some(testFile, tResult)
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
    val checker = TypeInfer(opts2)

    val sources = Vector(
        // broad removed the terra version - the main version doesn't work because it's import statement uses
        // a relative local path path instead of a URL
        //"https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping.wdl",
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
      val url = UUtil.getUrl(src)
      val doc = parser.parseDocument(url)
      checker.apply(doc)
    }
  }

  private val structsDir =
    Paths.get(getClass.getResource("/types/v1/structs").getPath)

  it should "handle several struct definitions" taggedAs Edge in {
    val opts2 = TypeOptions(
        antlr4Trace = false,
        localDirectories = Vector(structsDir),
        verbosity = Verbosity.Quiet,
        followImports = true
    )
    val checker = TypeInfer(opts2)
    val sourceFile = UUtil.pathToUrl(structsDir.resolve("file3.wdl"))
    val doc = Parsers(opts2).parseDocument(sourceFile)
    checker.apply(doc)
  }
}
