package wdlTools

import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
import collection.JavaConverters._

import org.scalatest.Tag
object Edge extends Tag("edge")

class Antlr4Test extends FlatSpec with Matchers {

  // Ignore a value. This is useful for avoiding warnings/errors
  // on unused variables.
  private def ignoreValue[A](value: A): Unit = {}

  private def getWdlSource(fname: String): String = {
    val p : String = getClass.getResource(s"/${fname}").getPath
    val path : Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }


  it should "handle various types" in {
    val doc = ConcreteSyntax.apply(getWdlSource("types.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  it should "handle types and expressions" in {
    val doc = ConcreteSyntax.apply(getWdlSource("expressions.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  it should "parse a task with an output section only" in {
    val doc = ConcreteSyntax.apply(getWdlSource("task_with_output_section.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse a task" taggedAs(Edge) in {
    val doc = ConcreteSyntax.apply(getWdlSource("task_wc.wdl"))
    System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse structs" in {
    val doc = ConcreteSyntax.apply(getWdlSource("struct.wdl"))
    System.out.println(doc)
    ignoreValue(doc)
  }
}
