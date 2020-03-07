package wdlTools

import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
import collection.JavaConverters._

import org.scalatest.Tag
object Edge extends Tag("edge")

import ConcreteSyntax._

class Antlr4Test extends FlatSpec with Matchers {

  // Ignore a value. This is useful for avoiding warnings/errors
  // on unused variables.
  private def ignoreValue[A](value: A): Unit = {}

  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  it should "handle various types" in {
    val doc = ConcreteSyntax.apply(getWdlSource("tasks", "types.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  it should "handle types and expressions" in {
    val doc = ConcreteSyntax.apply(getWdlSource("tasks", "expressions.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  it should "parse a task with an output section only" in {
    val doc = ConcreteSyntax.apply(getWdlSource("tasks", "output_section.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse a task" in {
    val doc = ConcreteSyntax.apply(getWdlSource("tasks", "wc.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "detect when a task section appears twice" in {
    assertThrows[Exception] {
      ConcreteSyntax.apply(getWdlSource("tasks", "multiple_input_section.wdl"))
    }
  }

  it should "handle string interpolation" taggedAs (Edge) in {
    val doc = ConcreteSyntax.apply(getWdlSource("tasks", "interpolation.wdl"))
    System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse structs" in {
    val doc = ConcreteSyntax.apply(getWdlSource("structs", "I.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse a simple workflow" in {
    val doc = ConcreteSyntax.apply(getWdlSource("workflows", "I.wdl"))
    doc.elements.size shouldBe (1)

    doc.version shouldBe ("1.0")
    val wf1 = doc.elements(0)
    wf1 shouldBe a[Workflow]
    val wf = wf1.asInstanceOf[Workflow]

    wf.name shouldBe ("biz")
    wf.body.size shouldBe (3)
  }
}
