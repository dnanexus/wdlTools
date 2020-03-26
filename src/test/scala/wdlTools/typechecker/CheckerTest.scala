package wdlTools.typechecker

import collection.JavaConverters._
import java.nio.file.{Files, Path, Paths}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.util.Util.Conf
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.ParseAll

class CheckerTest extends FlatSpec with Matchers {

  private val conf = Conf(antlr4Trace = false)
  private val stdlib = Stdlib(conf)
  private val parser = new ParseAll(conf)
  private val checker = new Checker(stdlib, conf)

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

  it should "type check simple declarations" in {
    val decl = Declaration("a", TypeInt, None)
    checker.applyDecl(decl, Map.empty)

    val decl2 = Declaration("a", TypeInt, Some(ValueInt(13)))
    checker.applyDecl(decl2, Map.empty)
  }

  it should "type check tasks (positive cases)" in {
    val positiveCases = getWdlSourceFiles("/typechecking/tasks/positive")
    for (pc <- positiveCases) {
      val wdlSourceCode = Files.readAllLines(pc).asScala.mkString(System.lineSeparator())
      val doc = parser.apply(wdlSourceCode)
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
      val wdlSourceCode = Files.readAllLines(pc).asScala.mkString(System.lineSeparator())
      val doc = parser.apply(wdlSourceCode)
      val checkVal =
        try {
          checker.apply(doc)
          false
        } catch {
          case e: Throwable =>
            // This file should NOT pass type validation
            true
        }
      if (!checkVal)
        throw new RuntimeException(s"Type error missed in file ${pc}")
    }
  }
}
