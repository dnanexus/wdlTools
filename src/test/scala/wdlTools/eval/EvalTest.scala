package wdlTools.eval

import java.nio.file.{Path, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.eval.WdlValues._
import wdlTools.syntax.Parsers
import wdlTools.util.{FileSourceResolver, Logger, FileUtils => UUtil}
import wdlTools.types.{TypeCheckingRegime, TypeInfer, TypedAbstractSyntax => TAT}

class EvalTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval/v1").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(srcDir))
  private val logger = Logger.Normal
  private val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val typeInfer = TypeInfer(regime = TypeCheckingRegime.Lenient)
  private val linesep = System.lineSeparator()
  private val evalPaths: EvalPaths = EvalPaths.createFromTemp()
  private val evalFileResolver = FileSourceResolver.create(Vector(evalPaths.getWorkDir()))

  def parseAndTypeCheck(file: Path): TAT.Document = {
    val doc = parsers.parseDocument(fileResolver.fromPath(UUtil.absolutePath(file)))
    val (tDoc, _) = typeInfer.apply(doc)
    tDoc
  }

  def parseAndTypeCheckAndGetDeclarations(
      file: Path,
      allowNonstandardCoercions: Boolean = false
  ): (Eval, Vector[TAT.PrivateVariable]) = {
    val tDoc = parseAndTypeCheck(file)
    val evaluator =
      Eval(evalPaths,
           Some(wdlTools.syntax.WdlVersion.V1),
           evalFileResolver,
           Logger.Quiet,
           allowNonstandardCoercions)
    tDoc.workflow.nonEmpty shouldBe true
    val wf = tDoc.workflow.get
    val decls: Vector[TAT.PrivateVariable] = wf.body.collect {
      case x: TAT.PrivateVariable => x
    }
    (evaluator, decls)
  }

  it should "handle simple expressions" in {
    val file = srcDir.resolve("simple_expr.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val bindings = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)
    bindings("k0") shouldBe V_Int(-1)
    bindings("k1") shouldBe V_Int(1)

    bindings("b1") shouldBe V_Boolean(true)
    bindings("b2") shouldBe V_Boolean(false)
    bindings("b3") shouldBe V_Boolean(10 == 3)
    bindings("b4") shouldBe V_Boolean(4 < 8)
    bindings("b5") shouldBe V_Boolean(4 >= 8)
    bindings("b6") shouldBe V_Boolean(4 != 8)
    bindings("b7") shouldBe V_Boolean(4 <= 8)
    bindings("b8") shouldBe V_Boolean(11 > 8)

    // Arithmetic
    bindings("i1") shouldBe V_Int(3 + 4)
    bindings("i2") shouldBe V_Int(3 - 4)
    bindings("i3") shouldBe V_Int(3 % 4)
    bindings("i4") shouldBe V_Int(3 * 4)
    bindings("i5") shouldBe V_Int(3 / 4)

    bindings("l0") shouldBe V_String("a")
    bindings("l1") shouldBe V_String("b")

    // pairs
    bindings("l") shouldBe V_String("hello")
    bindings("r") shouldBe V_Boolean(true)

    // structs
    bindings("pr1") shouldBe V_Struct("Person",
                                      Map(
                                          "name" -> V_String("Jay"),
                                          "city" -> V_String("SF"),
                                          "age" -> V_Int(31)
                                      ))
    bindings("name") shouldBe V_String("Jay")
  }

  it should "call stdlib" in {
    val file = srcDir.resolve("stdlib.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val ctx = WdlValueBindings(Map("empty_string" -> V_Null))
    val bindings = evaluator.applyPrivateVariables(decls, ctx)

    bindings("x") shouldBe V_Float(1.4)
    bindings("n1") shouldBe V_Int(1)
    bindings("n2") shouldBe V_Int(2)
    bindings("n3") shouldBe V_Int(1)
    bindings("cities2") shouldBe V_Array(
        Vector(V_String("LA"), V_String("Seattle"), V_String("San Francisco"))
    )

    bindings("table2") shouldBe V_Array(
        Vector(
            V_Array(Vector(V_String("A"), V_String("allow"))),
            V_Array(Vector(V_String("B"), V_String("big"))),
            V_Array(Vector(V_String("C"), V_String("clam")))
        )
    )
    bindings("m2") shouldBe V_Map(
        Map(V_String("name") -> V_String("hawk"), V_String("kind") -> V_String("bird"))
    )

    // sub
    bindings("sentence1") shouldBe V_String(
        "She visited three places on his trip: Aa, Ab, C, D, and E"
    )
    bindings("sentence2") shouldBe V_String(
        "He visited three places on his trip: Berlin, Berlin, C, D, and E"
    )
    bindings("sentence3") shouldBe V_String("H      : A, A, C, D,  E")

    // transpose
    bindings("ar3") shouldBe V_Array(Vector(V_Int(0), V_Int(1), V_Int(2)))
    bindings("ar_ar2") shouldBe V_Array(
        Vector(
            V_Array(Vector(V_Int(1), V_Int(4))),
            V_Array(Vector(V_Int(2), V_Int(5))),
            V_Array(Vector(V_Int(3), V_Int(6)))
        )
    )

    // zip
    bindings("zlf") shouldBe V_Array(
        Vector(
            V_Pair(V_String("A"), V_Boolean(true)),
            V_Pair(V_String("B"), V_Boolean(false)),
            V_Pair(V_String("C"), V_Boolean(true))
        )
    )

    // cross
    inside(bindings("cln")) {
      case V_Array(vec) =>
        // the order of the cross product is unspecified
        Vector(
            V_Pair(V_String("A"), V_Int(1)),
            V_Pair(V_String("B"), V_Int(1)),
            V_Pair(V_String("C"), V_Int(1)),
            V_Pair(V_String("A"), V_Int(13)),
            V_Pair(V_String("B"), V_Int(13)),
            V_Pair(V_String("C"), V_Int(13))
        ).foreach(pair => vec contains pair)
    }

    bindings("l1") shouldBe V_Int(2)
    bindings("l2") shouldBe V_Int(6)

    bindings("files2") shouldBe V_Array(
        Vector(V_File("A"), V_File("B"), V_File("C"), V_File("G"), V_File("J"), V_File("K"))
    )

    bindings("pref2") shouldBe V_Array(
        Vector(V_String("i_1"), V_String("i_3"), V_String("i_5"), V_String("i_7"))
    )
    bindings("pref3") shouldBe V_Array(
        Vector(V_String("sub_1.0"), V_String("sub_3.4"), V_String("sub_5.1"))
    )

    bindings("sel1") shouldBe V_String("A")
    bindings("sel2") shouldBe V_String("Henry")
    bindings("sel3") shouldBe V_Array(Vector(V_String("Henry"), V_String("bear"), V_String("tree")))

    bindings("d1") shouldBe V_Boolean(true)
    bindings("d2") shouldBe V_Boolean(false)
    bindings("d3") shouldBe V_Boolean(true)

    // basename
    bindings("path1") shouldBe V_String("C.txt")
    bindings("path2") shouldBe V_String("nuts_and_bolts.txt")
    bindings("path3") shouldBe V_String("docs.md")
    bindings("path4") shouldBe V_String("C")
    bindings("path5") shouldBe V_String("C.txt")

    // read/write object
    val obj1 = V_Object(
        Map("author" -> V_String("Benjamin"),
            "year" -> V_String("1973"),
            "title" -> V_String("Color the sky green"))
    )
    val obj2 = V_Object(
        Map("author" -> V_String("Primo Levy"),
            "year" -> V_String("1975"),
            "title" -> V_String("The Periodic Table"))
    )
    bindings("o2") shouldBe obj1
    bindings("arObj") shouldBe V_Array(Vector(obj1, obj2))
    bindings("houseObj") shouldBe V_Object(
        Map("city" -> V_String("Seattle"),
            "team" -> V_String("Trail Blazers"),
            "zipcode" -> V_Int(98109))
    )
  }

  it should "perform coercions" in {
    val file = srcDir.resolve("coercions.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val bindings = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)

    bindings("i1") shouldBe V_Int(13)
    bindings("i3") shouldBe V_Int(8)

    bindings("x1") shouldBe V_Float(3)
    bindings("x2") shouldBe V_Float(13)
    bindings("x3") shouldBe V_Float(44.3)
    bindings("x4") shouldBe V_Float(44.3)
    bindings("x5") shouldBe V_Float(4.5)

    bindings("s1") shouldBe V_String("true")
    bindings("s2") shouldBe V_String("3")
    bindings("s3") shouldBe V_String("4.3")
    bindings("s4") shouldBe V_String("hello")
    bindings("s5") shouldBe V_Optional(V_String("hello"))
  }

  it should "perform non-standard coercions" in {
    val file = srcDir.resolve("non_standard_coercions.wdl")
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(file, allowNonstandardCoercions = true)
    val bindings = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)

    bindings("i2") shouldBe V_Int(13)
  }

  private def evalCommand(wdlSourceFileName: String): String = {
    val file = srcDir.resolve(wdlSourceFileName)
    val tDoc = parseAndTypeCheck(file)
    val evaluator =
      Eval(evalPaths, Some(wdlTools.syntax.WdlVersion.V1), evalFileResolver, Logger.Quiet)
    val elts: Vector[TAT.DocumentElement] = tDoc.elements
    elts.nonEmpty shouldBe true
    val task = tDoc.elements.head.asInstanceOf[TAT.Task]
    val ctx = evaluator.applyPrivateVariables(task.privateVariables, WdlValueBindings.empty)
    evaluator.applyCommand(task.command, ctx)
  }

  it should "evaluate simple command section" in {
    val command = evalCommand("command_simple.wdl")
    command shouldBe "We just discovered a new flower with 100 basepairs. Is that possible?"
  }

  it should "evaluate command section with some variables" in {
    val command = evalCommand("command2.wdl")
    command shouldBe "His trumpet playing is not bad"
  }

  it should "command section with several kinds of primitives" in {
    val command = evalCommand("command3.wdl")
    command shouldBe "His trumpet playing is good. There are 10 instruments in the band. It this true?"
  }

  it should "separator placeholder" in {
    val command = evalCommand("sep_placeholder.wdl")
    command shouldBe "We have lots of numbers here 1, 10, 100"
  }

  it should "boolean placeholder" in {
    val command = evalCommand("bool_placeholder.wdl")
    command shouldBe s"--no${linesep}--yes"
  }

  it should "default placeholder" in {
    val command = evalCommand("default_placeholder.wdl")
    command shouldBe "hello"
  }

  it should "strip common indent" in {
    val command = evalCommand("indented_command.wdl")
    command shouldBe " echo 'hello Steve'\necho 'how are you, Steve?'\n   echo 'goodbye Steve'"
  }

  it should "strip common indent in python heredoc" in {
    val command = evalCommand("python_heredoc.wdl")
    command shouldBe """python <<CODE
                       |  with open("/path/to/file.txt") as fp:
                       |    for line in fp:
                       |      if not line.startswith('#'):
                       |        print(line.strip())
                       |CODE""".stripMargin
  }

  it should "bad coercion" in {
    val file = srcDir.resolve("bad_coercion.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    assertThrows[EvalException] {
      val _ = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)
    }
  }

  it should "handle null and optionals" taggedAs Edge in {
    val file = srcDir.resolve("conditionals3.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val bd = evaluator.applyPrivateVariables(decls, WdlValueBindings(Map("i2" -> V_Null)))
    bd("powers10") shouldBe V_Array(Vector(V_Optional(V_Int(1)), V_Null, V_Optional(V_Int(100))))
  }

  it should "handle accessing pair values" in {
    val file = srcDir.resolve("pair.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    evaluator.applyPrivateVariables(decls, WdlValueBindings(Map("i2" -> V_Null)))
  }

  it should "handle empty stdout/stderr" in {
    val file = srcDir.resolve("empty_stdout.wdl")
    parseAndTypeCheck(file)
  }

  it should "evalConst" in {
    val allExpectedResults = Map(
        "flag" -> Some(WdlValues.V_Boolean(true)),
        "i" -> Some(WdlValues.V_Int(8)),
        "x" -> Some(WdlValues.V_Float(2.718)),
        "s" -> Some(WdlValues.V_String("hello world")),
        "ar1" -> Some(
            WdlValues.V_Array(
                Vector(WdlValues.V_String("A"), WdlValues.V_String("B"), WdlValues.V_String("C"))
            )
        ),
        "m1" -> Some(
            WdlValues.V_Map(
                Map(WdlValues.V_String("X") -> WdlValues.V_Int(1),
                    WdlValues.V_String("Y") -> WdlValues.V_Int(10))
            )
        ),
        "p" -> Some(WdlValues.V_Pair(WdlValues.V_Int(1), WdlValues.V_Int(12))),
        "j" -> Some(WdlValues.V_Int(8)),
        "k" -> None,
        "s2" -> Some(WdlValues.V_String("hello world")),
        "readme" -> None,
        "file2" -> None
    )

    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(srcDir.resolve("constants.wdl"))

    decls.foreach {
      case TAT.PrivateVariable(id, wdlType, expr, _) =>
        val expected: Option[WdlValues.V] = allExpectedResults(id)
        //println(s"${id} ${wdlType} ${expr} ${expected}")
        expected match {
          case None =>
            assertThrows[EvalException] {
              evaluator.applyConstAndCoerce(expr, wdlType)
            }
          case Some(x) =>
            val retval = evaluator.applyConstAndCoerce(expr, wdlType)
            retval shouldBe x
        }
      case other =>
        throw new Exception(s"Unexpected declaration ${other}")
    }
  }

  it should "not be able to access unsupported file protocols" in {
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(srcDir.resolve("bad_protocol.wdl"))
    decls match {
      case Vector(TAT.PrivateVariable(_, wdlType, expr, _)) =>
        assertThrows[EvalException] {
          evaluator.applyConstAndCoerce(expr, wdlType)
        }
      case other => throw new Exception(s"unexpected decl ${other}")
    }
  }
}
