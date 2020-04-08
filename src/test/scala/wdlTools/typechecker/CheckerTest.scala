package wdlTools.typechecker

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.{Options, SourceCode, URL}

class CheckerTest extends FlatSpec with Matchers {
  private lazy val wdlSourceDirs: Vector[Path] = {
    val p1: Path = Paths.get(getClass.getResource("/typechecker/v1_0/tasks/positive").getPath)
    val p2: Path = Paths.get(getClass.getResource("/typechecker/v1_0/workflows/positive").getPath)
    Vector(p1, p2)
  }
  private lazy val conf = Options(antlr4Trace = false, localDirectories = wdlSourceDirs)
  private val loader = SourceCode.Loader(conf)
  private val parser = ParseAll(conf, loader)
  private val stdlib = Stdlib(conf)
  private val checker = Checker(stdlib)

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(dirname: String): Vector[Path] = {
    val d: String = getClass.getResource(dirname).getPath
    val folder = Paths.get(d)
    Files.exists(folder) shouldBe true
    Files.isDirectory(folder) shouldBe true
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".wdl"))
  }

  it should "type check tasks (positive cases)" in {
    val positiveCases = getWdlSourceFiles("/typechecker/v1_0/tasks/positive")
    for (pc <- positiveCases) {
      val doc = parser.parse(URL.fromPath(pc))
      try {
        checker.apply(doc)
      } catch {
        case e: Throwable =>
          throw new RuntimeException(s"Type error in file ${pc}")
      }
    }
  }

  it should "type check tasks (negative cases)" in {
    val negativeCases = getWdlSourceFiles("/typechecker/v1_0/tasks/negative")
    for (pc <- negativeCases) {
      val doc = parser.parse(URL.fromPath(pc))
      val checkVal =
        try {
          checker.apply(doc)
          true
        } catch {
          case e: Throwable =>
            // This file should NOT pass type validation.
            // The exception is expected at this point.
            false
        }
      if (checkVal) {
        throw new RuntimeException(s"Type error missed in file ${pc}")
      }
    }
  }

  it should "type check workflows (positive cases)" taggedAs(Edge) in {
    val positiveCases =
      getWdlSourceFiles("/typechecker/v1_0/workflows/positive")
        .filter(p => p.toString contains "import")

    for (pc <- positiveCases) {
      val doc = parser.parse(URL.fromPath(pc))
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
    val negativeCases = getWdlSourceFiles("/typechecker/v1_0/workflows/negative")
    for (nc <- negativeCases) {
      val doc = parser.parse(URL.fromPath(nc))
      val checkVal =
        try {
          checker.apply(doc)
          true
        } catch {
          case e: Throwable =>
            // This file should NOT pass type validation
            // The exception is expected at this point.
            false
        }
      if (checkVal)
        throw new RuntimeException(s"Type error missed in file ${nc}")
    }
  }
}
