package wdlTools.eval

import java.nio.file.{Files, Path, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.eval.WdlValues._
import wdlTools.syntax.Parsers
import wdlTools.util.{FileSourceResolver, Logger, Util => UUtil}
import wdlTools.types.{TypeCheckingRegime, TypeInfer, TypeOptions, TypedAbstractSyntax => TAT}

class EvalTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval/v1").getPath)
  private val opts =
    TypeOptions(fileResolver = FileSourceResolver.create(Vector(srcDir)),
                typeChecking = TypeCheckingRegime.Lenient,
                logger = Logger.Normal)
  private val parsers = Parsers(opts)
  private val typeInfer = TypeInfer(opts)
  private val linesep = System.lineSeparator()

  def safeMkdir(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    } else {
      // Path exists, make sure it is a directory, and not a file
      if (!Files.isDirectory(path))
        throw new Exception(s"Path ${path} exists, but is not a directory")
    }
  }

  private lazy val evalCfg: EvalConfig = {
    val baseDir = Files.createTempDirectory("evalTest")
    val homeDir = baseDir.resolve("home")
    val tmpDir = baseDir.resolve("tmp")
    for (d <- Vector(baseDir, homeDir, tmpDir))
      safeMkdir(d)
    val stdout = baseDir.resolve("stdout")
    val stderr = baseDir.resolve("stderr")
    EvalConfig.make(homeDir, tmpDir, stdout, stderr)
  }

  def parseAndTypeCheck(file: Path): TAT.Document = {
    val doc = parsers.parseDocument(opts.fileResolver.fromPath(UUtil.absolutePath(file)))
    val (tDoc, _) = typeInfer.apply(doc)
    tDoc
  }

  def parseAndTypeCheckAndGetDeclarations(file: Path): (Eval, Vector[TAT.Declaration]) = {
    val tDoc = parseAndTypeCheck(file)
    val evaluator =
      Eval(opts, evalCfg, wdlTools.syntax.WdlVersion.V1, opts.fileResolver.fromPath(file))

    tDoc.workflow should not be empty
    val wf = tDoc.workflow.get

    val decls: Vector[TAT.Declaration] = wf.body.collect {
      case x: TAT.Declaration => x
    }

    (evaluator, decls)
  }

  it should "handle simple expressions" in {
    val file = srcDir.resolve("simple_expr.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val ctxEnd = evaluator.applyDeclarations(decls, Context(Map.empty))
    val bindings = ctxEnd.bindings
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
    val ctx = Context(Map.empty).addBinding("empty_string", V_Null)
    val ctxEnd = evaluator.applyDeclarations(decls, ctx)
    val bd = ctxEnd.bindings

    bd("x") shouldBe V_Float(1.4)
    bd("n1") shouldBe V_Int(1)
    bd("n2") shouldBe V_Int(2)
    bd("n3") shouldBe V_Int(1)
    bd("cities2") shouldBe V_Array(
        Vector(V_String("LA"), V_String("Seattle"), V_String("San Francisco"))
    )

    bd("table2") shouldBe V_Array(
        Vector(
            V_Array(Vector(V_String("A"), V_String("allow"))),
            V_Array(Vector(V_String("B"), V_String("big"))),
            V_Array(Vector(V_String("C"), V_String("clam")))
        )
    )
    bd("m2") shouldBe V_Map(
        Map(V_String("name") -> V_String("hawk"), V_String("kind") -> V_String("bird"))
    )

    // sub
    bd("sentence1") shouldBe V_String(
        "She visited three places on his trip: Aa, Ab, C, D, and E"
    )
    bd("sentence2") shouldBe V_String(
        "He visited three places on his trip: Berlin, Berlin, C, D, and E"
    )
    bd("sentence3") shouldBe V_String("H      : A, A, C, D,  E")

    // transpose
    bd("ar3") shouldBe V_Array(Vector(V_Int(0), V_Int(1), V_Int(2)))
    bd("ar_ar2") shouldBe V_Array(
        Vector(
            V_Array(Vector(V_Int(1), V_Int(4))),
            V_Array(Vector(V_Int(2), V_Int(5))),
            V_Array(Vector(V_Int(3), V_Int(6)))
        )
    )

    // zip
    bd("zlf") shouldBe V_Array(
        Vector(
            V_Pair(V_String("A"), V_Boolean(true)),
            V_Pair(V_String("B"), V_Boolean(false)),
            V_Pair(V_String("C"), V_Boolean(true))
        )
    )

    // cross
    inside(bd("cln")) {
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

    bd("l1") shouldBe V_Int(2)
    bd("l2") shouldBe V_Int(6)

    bd("files2") shouldBe V_Array(
        Vector(V_File("A"), V_File("B"), V_File("C"), V_File("G"), V_File("J"), V_File("K"))
    )

    bd("pref2") shouldBe V_Array(
        Vector(V_String("i_1"), V_String("i_3"), V_String("i_5"), V_String("i_7"))
    )
    bd("pref3") shouldBe V_Array(
        Vector(V_String("sub_1.0"), V_String("sub_3.4"), V_String("sub_5.1"))
    )

    bd("sel1") shouldBe V_String("A")
    bd("sel2") shouldBe V_String("Henry")
    bd("sel3") shouldBe V_Array(Vector(V_String("Henry"), V_String("bear"), V_String("tree")))

    bd("d1") shouldBe V_Boolean(true)
    bd("d2") shouldBe V_Boolean(false)
    bd("d3") shouldBe V_Boolean(true)

    // basename
    bd("path1") shouldBe V_String("C.txt")
    bd("path2") shouldBe V_String("nuts_and_bolts.txt")
    bd("path3") shouldBe V_String("docs.md")
    bd("path4") shouldBe V_String("C")
    bd("path5") shouldBe V_String("C.txt")

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
    bd("o2") shouldBe obj1
    bd("arObj") shouldBe V_Array(Vector(obj1, obj2))
    bd("houseObj") shouldBe V_Object(
        Map("city" -> V_String("Seattle"),
            "team" -> V_String("Trail Blazers"),
            "zipcode" -> V_Int(98109))
    )

  }

  it should "perform coercions" in {
    val file = srcDir.resolve("coercions.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val ctxEnd = evaluator.applyDeclarations(decls, Context(Map.empty))
    val bd = ctxEnd.bindings

    bd("i1") shouldBe V_Int(13)
    bd("i2") shouldBe V_Int(13)
    bd("i3") shouldBe V_Int(8)

    bd("x1") shouldBe V_Float(3)
    bd("x2") shouldBe V_Float(13)
    bd("x3") shouldBe V_Float(44.3)
    bd("x4") shouldBe V_Float(44.3)
    bd("x5") shouldBe V_Float(4.5)

    bd("s1") shouldBe V_String("true")
    bd("s2") shouldBe V_String("3")
    bd("s3") shouldBe V_String("4.3")
    bd("s4") shouldBe V_String("hello")
    bd("s5") shouldBe V_Optional(V_String("hello"))
  }

  private def evalCommand(wdlSourceFileName: String): String = {
    val file = srcDir.resolve(wdlSourceFileName)
    val tDoc = parseAndTypeCheck(file)
    val evaluator =
      Eval(opts, evalCfg, wdlTools.syntax.WdlVersion.V1, opts.fileResolver.fromPath(file))

    tDoc.elements should not be empty
    val task = tDoc.elements.head.asInstanceOf[TAT.Task]
    val ctx = evaluator.applyDeclarations(task.declarations, Context(Map.empty))
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
      val _ = evaluator.applyDeclarations(decls, Context(Map.empty))
    }
  }

  it should "handle null and optionals" taggedAs Edge in {
    val file = srcDir.resolve("conditionals3.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)

    val ctxEnd = evaluator.applyDeclarations(decls, Context(Map("i2" -> V_Null)))
    val bd = ctxEnd.bindings

    bd("powers10") shouldBe V_Array(Vector(V_Optional(V_Int(1)), V_Null, V_Optional(V_Int(100))))
  }

  it should "handle accessing pair values" in {
    val file = srcDir.resolve("pair.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    evaluator.applyDeclarations(decls, Context(Map("i2" -> V_Null)))
  }

  it should "handle empty stdout/stderr" in {
    val file = srcDir.resolve("empty_stdout.wdl")
    parseAndTypeCheck(file)
  }
}
