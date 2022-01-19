package wdlTools.types

import java.nio.file.{Files, Path, Paths}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import wdlTools.syntax.Parsers
import dx.util.{FileNode, FileSourceResolver, Logger}

import scala.jdk.CollectionConverters._

class TypeInferComplianceTest extends AnyWordSpec with Matchers {
  private val fileResolver = FileSourceResolver.create(
      Vector(
          Paths.get(getClass.getResource("/types/draft2").getPath),
          Paths.get(getClass.getResource("/types/v1").getPath),
          Paths.get(getClass.getResource("/types/v2").getPath)
      )
  )
  // private val logger = Logger(quiet = false, traceLevel = TraceLevel.VVerbose)
  private val logger = Logger.Quiet
  private val parser = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(folder: Path): Vector[Path] = {
    Files.exists(folder) shouldBe true
    Files.isDirectory(folder) shouldBe true
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".wdl"))
  }

  // Expected results for a test, and any additional flags required
  // to run it.
  private case class TResult(correct: Boolean, flags: Option[TypeCheckingRegime.Value] = None)

  private val draft2ControlTable: Vector[(String, TResult)] = Vector(
      ("call_with_defaults.wdl", TResult(correct = true)),
      ("population.wdl", TResult(correct = true)),
      ("object_fields.wdl", TResult(correct = true))
  )

  private val v1ControlTable: Vector[(String, TResult)] = Vector(
      // workflows
      ("census.wdl", TResult(correct = true)),
      ("compound_expr_bug.wdl", TResult(correct = true)),
      ("imports.wdl", TResult(correct = true)),
      ("linear.wdl", TResult(correct = true)),
      ("nested.wdl", TResult(correct = true)),
      ("coercions_questionable.wdl", TResult(correct = true, Some(TypeCheckingRegime.Lenient))),
      ("coercions_strict.wdl", TResult(correct = true, Some(TypeCheckingRegime.Strict))),
      ("bad_stdlib_calls.wdl", TResult(correct = false)),
      ("scatter_II.wdl", TResult(correct = false)),
      ("scatter_I.wdl", TResult(correct = false)),
      ("shadow_II.wdl", TResult(correct = false)),
      ("shadow.wdl", TResult(correct = false)),
      ("polymorphic_types.wdl", TResult(correct = true)),
      ("empty_array_in_call.wdl", TResult(correct = true, Some(TypeCheckingRegime.Strict))),
      // missing arguments that may be supplied with a companion json inputs file
      ("missing_args.wdl", TResult(correct = true)),
      // correct tasks
      ("command_string.wdl", TResult(correct = true)),
      ("comparisons.wdl", TResult(correct = true)),
      ("library.wdl", TResult(correct = true)),
      ("empty_array_coercion.wdl", TResult(correct = true)),
      ("nonempty_array_coercion.wdl", TResult(correct = true)),
      // has a string -> int conversion that is allowed under
      // lenient type checking but disallowed under >= moderate
      ("simple.wdl", TResult(correct = true, Some(TypeCheckingRegime.Lenient))),
      ("stdlib.wdl", TResult(correct = true)),
      ("input_section.wdl", TResult(correct = true)),
      ("output_section.wdl", TResult(correct = true)),
      ("echo-pairs.wdl", TResult(correct = true)),
      ("nested_optional.wdl", TResult(correct = true)),
      ("optional_placeholder_value.wdl", TResult(correct = true, Some(TypeCheckingRegime.Lenient))),
      ("array_coersion.wdl", TResult(correct = true)),
      // incorrect tasks
      ("comparison1.wdl", TResult(correct = false)),
      ("comparison2.wdl", TResult(correct = false)),
      ("comparison4.wdl", TResult(correct = false, Some(TypeCheckingRegime.Lenient))),
      ("declaration_shadowing.wdl", TResult(correct = false)),
      ("simple.wdl", TResult(correct = false)),
      // expressions
      ("expressions.wdl", TResult(correct = true)),
      ("expressions_bad.wdl", TResult(correct = false)),
      ("map_index.wdl", TResult(correct = true)),
      // metadata
      ("meta_null_value.wdl", TResult(correct = true)),
      ("meta_section_compound.wdl", TResult(correct = true)),
      ("invalid_param_meta.wdl", TResult(correct = false, Some(TypeCheckingRegime.Strict))),
      // runtime section
      ("runtime_section_I.wdl", TResult(correct = true)),
      ("runtime_section_bad.wdl", TResult(correct = false))
  )

  private val v2ControlTable: Vector[(String, TResult)] = Vector(
//      ("v2comparison.wdl", TResult(correct = false))
      ("output_section.wdl", TResult(correct = true))
  )

  // test to include/exclude
  private val includeList: Option[Set[String]] = None
  private val excludeList: Option[Set[String]] = None

  private def checkCorrect(file: FileNode, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val checker =
      TypeInfer(regime = flag.getOrElse(TypeCheckingRegime.Moderate),
                fileResolver = fileResolver,
                logger = logger)
    try {
      val doc = parser.parseDocument(file)
      checker.apply(doc)
    } catch {
      case e: Throwable =>
        logger.error(s"Type error in file ${file.toString}")
        throw e
    }
  }

  private def checkIncorrect(file: FileNode, flag: Option[TypeCheckingRegime.Value]): Unit = {
    val checker =
      TypeInfer(regime = flag.getOrElse(TypeCheckingRegime.Moderate),
                fileResolver = fileResolver,
                logger = logger)
    val checkVal =
      try {
        val doc = parser.parseDocument(file)
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

  private def complianceTest(controlTable: Vector[(String, TResult)], resourceDir: String): Unit = {
    val testFiles: Vector[(FileNode, TResult)] = {
      val testFiles = getWdlSourceFiles(
          Paths.get(getClass.getResource(resourceDir).getPath)
      ).map(p => p.getFileName.toString -> p).toMap
      // filter out files that do not appear in the control table
      controlTable.collect {
        case (fileName, result) if testFiles.contains(fileName) && includeExcludeCheck(fileName) =>
          (fileResolver.fromPath(testFiles(fileName)), result)
      }
    }
    testFiles.foreach {
      case (testFile, TResult(true, flag)) =>
        s"type check valid WDL at ${testFile.name}" in {
          checkCorrect(testFile, flag)
        }
      case (testFile, TResult(false, flag)) =>
        s"fail to type check invalid WDL at ${testFile.name}" in {
          checkIncorrect(testFile, flag)
        }
    }
  }

  "draft2 compliance test" should {
    complianceTest(draft2ControlTable, "/types/draft2")
  }

  "v1 compliance test" should {
    complianceTest(v1ControlTable, "/types/v1")
  }

  "v2 compliance test" should {
    complianceTest(v2ControlTable, "/types/v2")
  }
}
