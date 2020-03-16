package wdlTools

import ConcreteSyntax._
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
//import org.scalatest.Inside._
import collection.JavaConverters._

class ConcreteSyntaxTest extends FlatSpec with Matchers {

  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  it should "handle various types" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "types.wdl"), false)

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

  it should "handle types and expressions" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "expressions.wdl"), false)

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.declarations(0) shouldBe (Declaration("i", TypeInt, Some(ExprInt(3))))
    task.declarations(1) shouldBe (Declaration("s", TypeString, Some(ExprString("hello world"))))
    task.declarations(2) shouldBe (Declaration("x", TypeFloat, Some(ExprFloat(4.3))))
    task.declarations(3) shouldBe (Declaration("f", TypeFile, Some(ExprString("/dummy/x.txt"))))
    task.declarations(4) shouldBe (Declaration("b", TypeBoolean, Some(ExprBoolean(false))))

    // Logical expressions
    task.declarations(5) shouldBe (Declaration(
        "b2",
        TypeBoolean,
        Some(ExprLor(ExprBoolean(true), ExprBoolean(false)))
    ))
    task.declarations(6) shouldBe (Declaration(
        "b3",
        TypeBoolean,
        Some(ExprLand(ExprBoolean(true), ExprBoolean(false)))
    ))
    task.declarations(7) shouldBe (Declaration("b4",
                                               TypeBoolean,
                                               Some(ExprEqeq(ExprInt(3), ExprInt(5)))))
    task.declarations(8) shouldBe (Declaration("b5",
                                               TypeBoolean,
                                               Some(ExprLt(ExprInt(4), ExprInt(5)))))
    task.declarations(9) shouldBe (Declaration("b6",
                                               TypeBoolean,
                                               Some(ExprGte(ExprInt(4), ExprInt(5)))))
    task.declarations(10) shouldBe (Declaration("b7",
                                                TypeBoolean,
                                                Some(ExprNeq(ExprInt(6), ExprInt(7)))))
    task.declarations(11) shouldBe (Declaration("b8",
                                                TypeBoolean,
                                                Some(ExprLte(ExprInt(6), ExprInt(7)))))
    task.declarations(12) shouldBe (Declaration("b9",
                                                TypeBoolean,
                                                Some(ExprGt(ExprInt(6), ExprInt(7)))))
    task.declarations(13) shouldBe (Declaration("b10",
                                                TypeBoolean,
                                                Some(ExprNegate(ExprIdentifier("b2")))))

    // Arithmetic
    task
      .declarations(14) shouldBe (Declaration("j", TypeInt, Some(ExprAdd(ExprInt(4), ExprInt(5)))))
    task.declarations(15) shouldBe (Declaration("j1",
                                                TypeInt,
                                                Some(ExprMod(ExprInt(4), ExprInt(10)))))
    task.declarations(16) shouldBe (Declaration("j2",
                                                TypeInt,
                                                Some(ExprDivide(ExprInt(10), ExprInt(7)))))
    task.declarations(17) shouldBe (Declaration("j3", TypeInt, Some(ExprIdentifier("j"))))
    task.declarations(18) shouldBe (Declaration("j4",
                                                TypeInt,
                                                Some(ExprAdd(ExprIdentifier("j"), ExprInt(19)))))

    task.declarations(19) shouldBe (Declaration(
        "ia",
        TypeArray(TypeInt, false),
        Some(ExprArrayLiteral(Vector(ExprInt(1), ExprInt(2), ExprInt(3))))
    ))
    task.declarations(20) shouldBe (Declaration("ia",
                                                TypeArray(TypeInt, true),
                                                Some(ExprArrayLiteral(Vector(ExprInt(10))))))
    task.declarations(21) shouldBe (Declaration("k",
                                                TypeInt,
                                                Some(ExprAt(ExprIdentifier("ia"), ExprInt(3)))))
    task.declarations(22) shouldBe (Declaration(
        "k2",
        TypeInt,
        Some(ExprApply("f", Vector(ExprInt(1), ExprInt(2), ExprInt(3))))
    ))
    task.declarations(23) shouldBe (Declaration(
        "m",
        TypeMap(TypeInt, TypeString),
        Some(ExprMapLiteral(Map(ExprInt(1) -> ExprString("a"), ExprInt(2) -> ExprString("b"))))
    ))
    task.declarations(24) shouldBe (Declaration(
        "k3",
        TypeInt,
        Some(ExprIfThenElse(ExprBoolean(true), ExprInt(1), ExprInt(2)))
    ))
    task.declarations(25) shouldBe (Declaration("k4",
                                                TypeInt,
                                                Some(ExprGetName(ExprIdentifier("x"), "a"))))

    task.declarations(26) shouldBe (Declaration(
        "o",
        TypeObject,
        Some(ExprObjectLiteral(Map("A" -> ExprInt(1), "B" -> ExprInt(2))))
    ))
    task.declarations(27) shouldBe (Declaration(
        "twenty_threes",
        TypePair(TypeInt, TypeString),
        Some(ExprPair(ExprInt(23), ExprString("twenty-three")))
    ))
  }

  it should "handle get name" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "get_name_bug.wdl"), false)

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe ("district")
    task.input shouldBe (None)
    task.output shouldBe (None)
    task.command shouldBe (CommandSection(Vector()))
    task.meta shouldBe (None)
    task.parameterMeta shouldBe (None)

    task.declarations(0) shouldBe (Declaration("k",
                                               TypeInt,
                                               Some(ExprGetName(ExprIdentifier("x"), "a"))))
  }

  it should "detect a wrong comment style" in {

    assertThrows[Exception] {
      ParseDocument.apply(getWdlSource("tasks", "wrong_comment_style.wdl"), false, quiet = true)
    }
  }

  it should "parse a task with an output section only" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "output_section.wdl"), false)

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe ("wc")
    task.output shouldBe (Some(
        OutputSection(
            Vector(
                Declaration("num_lines", TypeInt, Some(ExprInt(3)))
            )
        )
    ))
  }

  it should "parse a task" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "wc.wdl"), false)

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe ("wc")
    task.input shouldBe (Some(InputSection(Vector(Declaration("inp_file", TypeFile, None)))))
    task.output shouldBe (Some(
        OutputSection(Vector(Declaration("num_lines", TypeInt, Some(ExprInt(3)))))
    ))
    task.command shouldBe a[CommandSection]
    task.command.parts should contain(ExprIdentifier("inp_file"))
    task.command.parts should contain(ExprString("\n    wc -l "))
    //task.command.parts should contain(ExprString("\n"))

    task.meta shouldBe (Some(MetaSection(Vector(MetaKV("author", ExprString("Robin Hood"))))))
    task.parameterMeta shouldBe (Some(
        ParameterMetaSection(Vector(MetaKV("reason", ExprString("just because"))))
    ))
    task.declarations(0) shouldBe (Declaration("i", TypeInt, Some(ExprAdd(ExprInt(4), ExprInt(5)))))
  }

  it should "detect when a task section appears twice" in {
    assertThrows[Exception] {
      ParseDocument.apply(getWdlSource("tasks", "multiple_input_section.wdl"), false)
    }
  }

  it should "handle string interpolation" in {
    val doc = ParseDocument.apply(getWdlSource("tasks", "interpolation.wdl"), false)

    doc.version shouldBe ("1.0")
    doc.elements.size shouldBe (1)
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe ("foo")
    task.input shouldBe (Some(
        InputSection(
            Vector(Declaration("min_std_max_min", TypeInt, None),
                   Declaration("prefix", TypeString, None))
        )
    ))
    task.command shouldBe a[CommandSection]
    task.command.parts should contain(ExprString("\n    echo "))
    task.command.parts should contain(
        ExprPlaceholderSep(ExprString(","), ExprIdentifier("min_std_max_min"))
    )
  }

  it should "parse structs" in {
    val doc = ParseDocument.apply(getWdlSource("structs", "I.wdl"), false)

    doc.version shouldBe ("1.0")
    val structs = doc.elements.collect {
      case x: TypeStruct => x
    }
    structs.size shouldBe (2)
    structs(0) shouldBe TypeStruct(
        "Address",
        Map("street" -> TypeString, "city" -> TypeString, "zipcode" -> TypeInt)
    )
    structs(1) shouldBe TypeStruct(
        "Data",
        Map("history" -> TypeFile, "date" -> TypeInt, "month" -> TypeString)
    )
  }

  it should "parse a simple workflow" taggedAs (Edge) in {
    val doc = ParseDocument.apply(getWdlSource("workflows", "I.wdl"), false)
    doc.elements.size shouldBe (0)

    doc.version shouldBe ("1.0")
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.name shouldBe ("biz")
    wf.body.size shouldBe (3)

    val calls = wf.body.collect {
      case x: Call => x
    }
    calls.size shouldBe (1)
    calls(0) shouldBe (Call(name = "bar",
                            alias = Some("boz"),
                            inputs = Map("i" -> ExprIdentifier("s"))))

    val scatters = wf.body.collect {
      case x: Scatter => x
    }
    scatters.size shouldBe (1)
    scatters(0).identifier shouldBe ("i")
    scatters(0).expr shouldBe (ExprArrayLiteral(Vector(ExprInt(1), ExprInt(2), ExprInt(3))))
    scatters(0).body shouldBe (Vector(
        Call(name = "add", alias = None, inputs = Map("x" -> ExprIdentifier("i")))
    ))

    val conditionals = wf.body.collect {
      case x: Conditional => x
    }
    conditionals.size shouldBe (1)
    conditionals(0).expr shouldBe (ExprEqeq(ExprBoolean(true), ExprBoolean(false)))
    conditionals(0).body shouldBe (Vector(Call("sub", None, Map.empty)))

    wf.meta shouldBe (Some(MetaSection(Vector(MetaKV("author", ExprString("Robert Heinlein"))))))
  }

  it should "handle import statements" in {
    val doc = ParseDocument.apply(getWdlSource("workflows", "imports.wdl"), false)

    doc.version shouldBe ("1.0")

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe (2)

    doc.workflow should not be empty
  }
}
