package wdlTools

import ConcreteSyntax._
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
import collection.JavaConverters._

import org.scalatest.Tag
object Edge extends Tag("edge")

class ConcreteSyntaxTest extends FlatSpec with Matchers {

  // Ignore a value. This is useful for avoiding warnings/errors
  // on unused variables.
  private def ignoreValue[A](value: A): Unit = {}

  private lazy val wdlSourceDirs: Vector[Path] = {
    val p1: Path = Paths.get(getClass.getResource("/workflows").getPath)
    val p2: Path = Paths.get(getClass.getResource("/tasks").getPath)
    Vector(p1, p2)
  }

  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  it should "handle various types" in {
    val pa = new ParseAll()
    val doc = pa.apply(getWdlSource("tasks", "types.wdl"))

    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    val InputSection(decls) = task.input.get
    decls shouldBe (
        Vector(
            Declaration("i", TypeInt, None),
            Declaration("s", TypeString, None),
            Declaration("x", TypeFloat, None),
            Declaration("b", TypeBoolean, None),
            Declaration("f", TypeFile, None),
            Declaration("p1", TypePair(TypeInt, TypeString), None),
            Declaration("p2", TypePair(TypeFloat, TypeFile), None),
            Declaration("p3", TypePair(TypeBoolean, TypeBoolean), None),
            Declaration("ia", TypeArray(TypeInt, false), None),
            Declaration("sa", TypeArray(TypeString, false), None),
            Declaration("xa", TypeArray(TypeFloat, false), None),
            Declaration("ba", TypeArray(TypeBoolean, false), None),
            Declaration("fa", TypeArray(TypeFile, false), None),
            Declaration("m_si", TypeMap(TypeInt, TypeString), None),
            Declaration("m_ff", TypeMap(TypeFile, TypeFile), None),
            Declaration("m_bf", TypeMap(TypeBoolean, TypeFloat), None)
        )
    )
  }

  it should "handle types and expressions" taggedAs (Edge) in {
    val pa = new ParseAll(antlr4Trace = false)
    val doc = pa.apply(getWdlSource("tasks", "expressions.wdl"))

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    val numDecl = task.declarations.size
    task.declarations.dropRight(numDecl - 7) shouldBe (
        Vector(
            Declaration("i", TypeInt, Some(ExprInt(3))),
            Declaration("s", TypeString, Some(ExprString("hello world"))),
            Declaration("x", TypeFloat, Some(ExprFloat(4.3))),
            Declaration("f", TypeFile, Some(ExprString("/dummy/x.txt"))),
            Declaration("b", TypeBoolean, Some(ExprBoolean(false))),
            // Logical expressions
            Declaration("b2", TypeBoolean, Some(ExprLor(ExprBoolean(true), ExprBoolean(false)))),
            Declaration("b3", TypeBoolean, Some(ExprLand(ExprBoolean(true), ExprBoolean(false))))
        )
    )
    /*Declaration("b4", TypeBoolean, ExprEq(ExprInt(3) == ExprInt5)),
Declaration("b5", TypeBoolean, ExprBoolean 4 < 5)),
Declaration("b6", TypeBoolean, ExprBoolean 4 >= 5)),
Declaration("b7", TypeBoolean, ExprBoolean 6 != 7)),
Declaration("b8", TypeBoolean, ExprBoolean 6 <= 7)),
Declaration("b9", TypeBoolean, ExprBoolean 6 > 7)),
Declaration("b10", TypeBoolean, ExprBoolean= !b2)),

                                  // Arithmetic
Declaration(  Int j = 4 + 5),
Declaration(  Int j1 = 4 % 10),
Declaration(  Int j2 = 10 / 7),
Declaration(  Int j3 = j),
Declaration(  Int j4 = j + 19),

Declaration(  Array[Int] ia = [1, 2, 3]),
Declaration(  Array[Int]+ ia = [10]),
Declaration(  Int k = ia[3]),
Declaration(  Int k2 = f(1, 2, 3)),
Declaration(  Map[Int, String] = {1 : "a", 2: "b"}),
Declaration(  Int k3 = if (true) then 1 else 2),
Declaration(  Int k4 = x.a),
Declaration(  Object o = { A : 1, B : 2 }),
Declaration(  Pair[Int, String] twenty_threes = (23, "twenty-three")),
   */
  }

  it should "parse a task with an output section only" in {
    val pa = new ParseAll()
    val doc = pa.apply(getWdlSource("tasks", "output_section.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse a task" in {
    val pa = new ParseAll()
    val doc = pa.apply(getWdlSource("tasks", "wc.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "detect when a task section appears twice" in {
    val pa = new ParseAll()
    assertThrows[Exception] {
      pa.apply(getWdlSource("tasks", "multiple_input_section.wdl"))
    }
  }

  it should "handle string interpolation" in {
    val pa = new ParseAll(antlr4Trace = false)
    val doc = pa.apply(getWdlSource("tasks", "interpolation.wdl"))
//    System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse structs" in {
    val pa = new ParseAll()
    val doc = pa.apply(getWdlSource("structs", "I.wdl"))
    //System.out.println(doc)
    ignoreValue(doc)
  }

  it should "parse a simple workflow" in {
    val pa = new ParseAll()
    val doc = pa.apply(getWdlSource("workflows", "I.wdl"))
    doc.elements.size shouldBe (0)

    doc.version shouldBe ("1.0")
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.name shouldBe ("biz")
    wf.body.size shouldBe (3)
  }

  it should "handle import statements" in {
    val pa = new ParseAll(antlr4Trace = false, localDirectories = wdlSourceDirs)
    val doc = pa.apply(getWdlSource("workflows", "imports.wdl"))

    doc.version shouldBe ("1.0")

    val imports = doc.elements.collect {
      case x: ImportDocElaborated => x
    }
    imports.size shouldBe (2)

    doc.workflow should not be empty
  }
}
