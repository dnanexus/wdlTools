package wdlTools.typechecker

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.util.{Options, URL}
import wdlTools.syntax.ParseAll

class CheckerTest extends FlatSpec with Matchers {

  private val conf = Options(antlr4Trace = false)
  private val stdlib = Stdlib(conf)
  private val parser = new ParseAll(conf)
  private val checker = new Checker(stdlib)

  // Get a list of WDL files from a resource directory.
  private def getWdlSourceFiles(dirname: String): Vector[Path] = {
    val d: String = getClass.getResource(dirname).getPath
    val folder = Paths.get(d)
    Files.exists(folder) shouldBe (true)
    Files.isDirectory(folder) shouldBe (true)
    val allFiles: Vector[Path] = Files.list(folder).iterator().asScala.toVector
    allFiles.filter {
      case p =>
        Files.isRegularFile(p) && p.toString.endsWith(".wdl")
    }
  }

  it should "type check tasks (positive cases)" in {
    val positiveCases = getWdlSourceFiles("/typechecking/tasks/positive")
    for (pc <- positiveCases) {
      val wdlSourceUrl = URL(pc.toString)
      val doc = parser.apply(wdlSourceUrl)
      try {
        checker.apply(doc)
      } catch {
        case e: Throwable =>
          throw new RuntimeException(s"Type error in file ${pc}")
      }
    }
  }

  it should "type check tasks (negative cases)" in {
    val negativeCases = getWdlSourceFiles("/typechecking/tasks/negative")
    for (pc <- negativeCases) {
      val wdlSourceUrl = URL(pc.toString)
      val doc = parser.apply(wdlSourceUrl)
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

  it should "type check workflows (positive cases)" in {
    val positiveCases = getWdlSourceFiles("/typechecking/workflows/positive")
    for (pc <- positiveCases) {
      val wdlSourceUrl = URL(pc.toString)
      val doc = parser.apply(wdlSourceUrl)
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
    val negativeCases = getWdlSourceFiles("/typechecking/workflows/negative")
    for (nc <- negativeCases) {
      val wdlSourceUrl = URL(nc.toString)
      val doc = parser.apply(wdlSourceUrl)
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
