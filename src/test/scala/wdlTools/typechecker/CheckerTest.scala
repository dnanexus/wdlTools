package wdlTools.typechecker

import collection.JavaConverters._
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.util.Util.Conf
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.ParseAll

class CheckerTest extends FlatSpec with Matchers {

  private val conf = Conf(antlr4Trace = false)
  private val stdlib = Stdlib(conf)
  private val parser = new ParseAll(conf)
  private val checker = new Checker(stdlib, conf)

  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/typechecking/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  it should "type check simple declarations" in {
    val decl = Declaration("a", TypeInt, None)
    checker.apply(decl, Map.empty)

    val decl2 = Declaration("a", TypeInt, Some(ValueInt(13)))
    checker.apply(decl2, Map.empty)
  }

  it should "type check a task" in {
    val doc = parser.apply(getWdlSource("tasks", "simple.wdl"))
    checker.apply(doc)
  }
}
