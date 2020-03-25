package wdlTools.typechecker

import collection.JavaConverters._
import scala.jdk.StreamConverters._
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

/*  private def getWdlSource(dirname: String, dirname2 : String fname: String): String = {
    val p: String = getClass.getResource(s"/typechecking/${dirname}/${dirname2}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  } */

  it should "type check simple declarations" in {
    val decl = Declaration("a", TypeInt, None)
    checker.apply(decl, Map.empty)

    val decl2 = Declaration("a", TypeInt, Some(ValueInt(13)))
    checker.apply(decl2, Map.empty)
  }

  it should "type check tasks" in {
    val d : String = getClass.getResource("/typechecking/tasks/positive").getPath
    //val folder = new File(d.getPath)
    val folder = Paths.get(d)
    Files.exists(folder) shouldBe(true)
    Files.isDirectory(folder) shouldBe(true)
    val positiveCases = Files.list(folder).asScala.toVector
    System.out.println(positiveCases)

    for (pc <- positiveCases) {
      val p = Paths.get(pc)
      val wdlSourceCode = Files.readAllLines(p).asScala.mkString(System.lineSeparator())
      val doc = parser.apply(wdlSourceCode)
      checker.apply(doc)
    }
  }
}
