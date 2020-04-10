package wdlTools.typing

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.{Options, SourceCode, Util, Verbosity}

class TypeCheckerTest extends FlatSpec with Matchers {
  private val opts = Options(
      antlr4Trace = false,
      localDirectories = Some(
          Vector(
              Paths.get(getClass.getResource("/typing/v1_0/tasks/positive").getPath),
              Paths.get(getClass.getResource("/typing/v1_0/tasks/negative").getPath),
              Paths.get(getClass.getResource("/typing/v1_0/workflows/positive").getPath),
              Paths.get(getClass.getResource("/typing/v1_0/workflows/negative").getPath)
          )
      ),
      verbosity = Verbosity.Quiet
  )
  private val loader = SourceCode.Loader(opts)
  private val parser = ParseAll(opts, loader)
  private val stdlib = Stdlib(opts)
  private val checker = TypeChecker(stdlib)

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(folder: Path): Vector[Path] = {
    Files.exists(folder) shouldBe true
    Files.isDirectory(folder) shouldBe true
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".wdl"))
  }

  it should "type check tasks (positive cases)" in {
    val positivePath =
      Paths.get(getClass.getResource("/typing/v1_0/tasks/positive").getPath)
    val positiveCases = getWdlSourceFiles(positivePath)
    for (pc <- positiveCases) {
      val doc = parser.parse(Util.getURL(pc))
      try {
        checker.apply(doc)
      } catch {
        case _: Throwable =>
          throw new RuntimeException(s"Type error in file ${pc}")
      }
    }
  }

  it should "type check tasks (negative cases)" in {
    val negativePath =
      Paths.get(getClass.getResource("/typing/v1_0/tasks/negative").getPath)
    val negativeCases = getWdlSourceFiles(negativePath)
    for (pc <- negativeCases) {
      val doc = parser.parse(Util.getURL(pc))
      val checkVal =
        try {
          checker.apply(doc)
          true
        } catch {
          case _: Throwable =>
            // This file should NOT pass type validation.
            // The exception is expected at this point.
            false
        }
      if (checkVal) {
        throw new RuntimeException(s"Type error missed in file ${pc}")
      }
    }
  }

  it should "type check workflows (positive cases)" in {
    val positivePath =
      Paths.get(getClass.getResource("/typing/v1_0/workflows/positive").getPath)
    val positiveCases = getWdlSourceFiles(positivePath)
    for (pc <- positiveCases) {
      val doc = parser.parse(Util.getURL(pc))
      try {
        checker.apply(doc)
      } catch {
        case e: Throwable =>
          System.out.println(s"Type error in file ${pc}")
          throw e
      }
    }
  }

  it should "type check workflows (negative cases)" in {
    val negativePath =
      Paths.get(getClass.getResource("/typing/v1_0/workflows/negative").getPath)
    val negativeCases = getWdlSourceFiles(negativePath)
    for (nc <- negativeCases) {
      val doc = parser.parse(Util.getURL(nc))
      val checkVal =
        try {
          checker.apply(doc)
          true
        } catch {
          case _: Throwable =>
            // This file should NOT pass type validation
            // The exception is expected at this point.
            false
        }
      if (checkVal)
        throw new RuntimeException(s"Type error missed in file ${nc}")
    }
  }

  ignore should "be able to handle GATK" taggedAs Edge in {
    val url = Util.getURL(
        "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping-terra.wdl"
    )
    val doc = parser.parse(url)
    checker.apply(doc)
  }

  it should "size stdlib" taggedAs Edge in {
    val doc = parser.parse(opts.getURL("stdlib.wdl"))
    checker.apply(doc)
  }
}