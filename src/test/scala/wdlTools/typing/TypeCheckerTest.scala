package wdlTools.typing

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.v1.ParseAll
import wdlTools.util.{Options, SourceCode, Util, Verbosity}
import wdlTools.util.TypeCheckingRegime._

class TypeCheckerTest extends FlatSpec with Matchers {
  private val opts = Options(
      antlr4Trace = false,
      localDirectories = Some(
          Vector(
              Paths.get(getClass.getResource("/typing/v1_0").getPath)
          )
      ),
      verbosity = Verbosity.Quiet,
      followImports = true
  )
  private val loader = SourceCode.Loader(opts)
  private val parser = ParseAll(opts, loader)

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(folder: Path): Vector[Path] = {
    Files.exists(folder) shouldBe true
    Files.isDirectory(folder) shouldBe true
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".wdl"))
  }

  // Expected results for a test, and any additional flags required
  // to run it.
  case class TResult(correct: Boolean, flags: Option[TypeCheckingRegime] = None)

  val controlTable: Map[String, TResult] = Map(
      // workflows
      "census.wdl" -> TResult(true),
      "compound_expr_bug.wdl" -> TResult(true),
      "imports.wdl" -> TResult(true),
      "linear.wdl" -> TResult(true),
      "nested.wdl" -> TResult(true),
      "types.wdl" -> TResult(true),
      "coercions_questionable.wdl" -> TResult(true, Some(Lenient)),
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
      "null.wdl" -> TResult(true),
      "simple.wdl" -> TResult(true),
      "stdlib.wdl" -> TResult(true),
      // incorrect tasks
      "comparison1.wdl" -> TResult(false),
      "comparison2.wdl" -> TResult(false),
      "comparison4.wdl" -> TResult(false),
      "declaration_shadowing.wdl" -> TResult(false),
      "simple.wdl" -> TResult(false)
  )

  // test to include/exclude
  private val includeList: Option[Set[String]] = None //Some(Set("coercions_questionable.wdl"))
  private val excludeList: Option[Set[String]] = None

  private def checkCorrect(file: Path, flag: Option[TypeCheckingRegime]): Unit = {
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
        System.out.println(e.getMessage)
        throw new RuntimeException(s"Type error in file ${file.toString}")
    }
  }

  private def checkIncorrect(file: Path, flag: Option[TypeCheckingRegime]): Unit = {
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

  it should "type check test wdl files" in {
    val testFiles = getWdlSourceFiles(
        Paths.get(getClass.getResource("/typing/v1_0").getPath)
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

  ignore should "coerce types" in {
    val tUtil = TUtil(opts)
    import WdlTypes._

    tUtil
      .isCoercibleTo(WT_Array(WT_Optional(WT_String)), WT_Array(WT_Optional(WT_String))) shouldBe (true)
  }

  it should "be able to handle GATK" taggedAs (Edge) in {
    val opts2 = opts.copy(typeChecking = Lenient)
    val stdlib = Stdlib(opts2)
    val checker = TypeChecker(stdlib)

    val sources = Vector(
        "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping-terra.wdl"
//      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping.wdl"
    )

    for (src <- sources) {
      val url = Util.getURL(src)
      val doc = parser.parse(url)
      checker.apply(doc)
    }
  }
}
