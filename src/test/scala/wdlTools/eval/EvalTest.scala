package wdlTools.eval

import java.nio.file.{Files, Path, Paths}
import dx.util.{EvalPaths, FileSourceResolver, FileUtils, Logger, SysUtils}
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{Parsers, SourceLocation, WdlVersion}
import wdlTools.types.{TypeCheckingRegime, TypeInfer, TypedAbstractSyntax => TAT}
import wdlTools.types.WdlTypes._

import java.io.File

class EvalTest extends AnyFlatSpec with Matchers with Inside {
  private val v1Dir = Paths.get(getClass.getResource("/eval/v1").getPath)
  private val v1_1Dir = Paths.get(getClass.getResource("/eval/v1.1").getPath)
  private val v2Dir = Paths.get(getClass.getResource("/eval/v2").getPath)
  private val execDir = Paths.get(getClass.getResource("/exec").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(v1Dir, v1_1Dir, v2Dir, execDir))
  private val logger = Logger.Normal
  private val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val typeInfer = TypeInfer(regime = TypeCheckingRegime.Lenient)
  private val linesep = System.lineSeparator()
  private val evalPaths: EvalPaths = DefaultEvalPaths.createFromTemp()
  private val evalFileResolver = FileSourceResolver.create(Vector(evalPaths.getWorkDir()))

  def parseAndTypeCheck(file: Path): TAT.Document = {
    val doc = parsers.parseDocument(fileResolver.fromPath(FileUtils.absolutePath(file)))
    val (tDoc, _) = typeInfer.apply(doc)
    tDoc
  }

  def createEvaluator(wdlVersion: WdlVersion = WdlVersion.V1,
                      allowNonstandardCoercions: Boolean = false): Eval = {
    Eval(evalPaths,
         Some(wdlVersion),
         Vector.empty,
         evalFileResolver,
         Logger.Quiet,
         allowNonstandardCoercions)
  }

  def parseAndTypeCheckAndGetDeclarations(
      file: Path,
      wdlVersion: WdlVersion = WdlVersion.V1,
      allowNonstandardCoercions: Boolean = false
  ): (Eval, Vector[TAT.PrivateVariable]) = {
    val tDoc = parseAndTypeCheck(file)
    val evaluator = createEvaluator(wdlVersion, allowNonstandardCoercions)
    tDoc.workflow.nonEmpty shouldBe true
    val wf = tDoc.workflow.get
    val decls: Vector[TAT.PrivateVariable] = wf.body.collect {
      case x: TAT.PrivateVariable => x
    }
    (evaluator, decls)
  }

  it should "handle simple expressions" in {
    val file = v1Dir.resolve("simple_expr.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val bindings =
      evaluator.applyPrivateVariables(decls, WdlValueBindings(Map("x" -> V_Float(1.0))))

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
    bindings("i6") shouldBe V_Float((0.4 + 0.8) / 2)
    bindings("i7") shouldBe V_Float(0.0)

    bindings("l0") shouldBe V_String("a")
    bindings("l1") shouldBe V_String("b")

    // escape sequences
    bindings("esc") shouldBe V_String("hello\n\t\"x\\o\"")
    bindings("esc_unicode") shouldBe V_String("\u274C")
    bindings("esc_unicode2") shouldBe V_String("🌭")
    bindings("esc_hex") shouldBe V_String("204")
    bindings("esc_oct") shouldBe V_String("23")

    // pairs
    bindings("l") shouldBe V_String("hello")
    bindings("r") shouldBe V_Boolean(true)

    // structs
    bindings("pr1") shouldBe V_Struct("Person",
                                      "name" -> V_String("Jay"),
                                      "city" -> V_String("SF"),
                                      "age" -> V_Int(31))
    bindings("name") shouldBe V_String("Jay")
  }

  it should "call stdlib" in {
    val file = v1Dir.resolve("stdlib.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val ctx = WdlValueBindings(Map("empty_string" -> V_Null))
    val bindings = evaluator.applyPrivateVariables(decls, ctx)

    bindings("x") shouldBe V_Float(1.4)
    bindings("n1") shouldBe V_Int(1)
    bindings("n2") shouldBe V_Int(2)
    bindings("n3") shouldBe V_Int(1)
    bindings("cities2") shouldBe V_Array(
        V_String("LA"),
        V_String("Seattle"),
        V_String("San Francisco")
    )

    bindings("table2") shouldBe V_Array(
        V_Array(V_String("A"), V_String("allow")),
        V_Array(V_String("B"), V_String("big")),
        V_Array(V_String("C"), V_String("clam"))
    )
    bindings("m2") shouldBe V_Map(
        V_String("name") -> V_String("hawk"),
        V_String("kind") -> V_String("bird")
    )

    // sub
    bindings("sentence1") shouldBe V_String(
        "She visited three places on his trip: Aa, Ab, C, D, and E"
    )
    bindings("sentence2") shouldBe V_String(
        "He visited three places on his trip: Berlin, Berlin, C, D, and E"
    )
    bindings("sentence3") shouldBe V_String("H      : A, A, C, D,  E")
    bindings("fname1") shouldBe V_String("file.bam.bai")

    // transpose
    bindings("ar3") shouldBe V_Array(V_Int(0), V_Int(1), V_Int(2))
    bindings("ar_ar2") shouldBe V_Array(
        V_Array(V_Int(1), V_Int(4)),
        V_Array(V_Int(2), V_Int(5)),
        V_Array(V_Int(3), V_Int(6))
    )

    // zip
    bindings("zlf") shouldBe V_Array(
        V_Pair(V_String("A"), V_Boolean(true)),
        V_Pair(V_String("B"), V_Boolean(false)),
        V_Pair(V_String("C"), V_Boolean(true))
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
        V_File("A"),
        V_File("B"),
        V_File("C"),
        V_File("G"),
        V_File("J"),
        V_File("K")
    )

    bindings("pref2") shouldBe V_Array(
        V_String("i_1"),
        V_String("i_3"),
        V_String("i_5"),
        V_String("i_7")
    )
    bindings("pref3") shouldBe V_Array(
        V_String("sub_1.0"),
        V_String("sub_3.4"),
        V_String("sub_5.1")
    )

    bindings("sel1") shouldBe V_String("A")
    bindings("sel2") shouldBe V_String("Henry")
    bindings("sel3") shouldBe V_Array(V_String("Henry"), V_String("bear"), V_String("tree"))

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
        "author" -> V_String("Benjamin"),
        "year" -> V_String("1973"),
        "title" -> V_String("Color the sky green")
    )
    val obj2 = V_Object(
        "author" -> V_String("Primo Levy"),
        "year" -> V_String("1975"),
        "title" -> V_String("The Periodic Table")
    )
    bindings("o2") shouldBe obj1
    bindings("arObj") shouldBe V_Array(obj1, obj2)
    bindings("houseObj") shouldBe V_Object(
        "city" -> V_String("Seattle"),
        "team" -> V_String("Trail Blazers"),
        "zipcode" -> V_Int(98109)
    )
  }

  it should "perform coercions" in {
    val f = Files.createTempFile("test", ".json")
    f.toFile.deleteOnExit()
    FileUtils.writeFileContent(f, """{"a": "hello", "b": "goodbye"}""")

    val file = v1Dir.resolve("coercions.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val bindings =
      evaluator.applyPrivateVariables(decls,
                                      WdlValueBindings(Map("json_file" -> V_File(f.toString))))

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

    bindings("mapFromObj") shouldBe V_Map(V_String("a") -> V_String("hello"),
                                          V_String("b") -> V_String("goodbye"))
  }

  it should "perform non-standard coercions" in {
    val file = v1Dir.resolve("non_standard_coercions.wdl")
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(file, allowNonstandardCoercions = true)
    val bindings = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)

    bindings("i2") shouldBe V_Int(13)
  }

  private def evalCommand(wdlSourceFileName: String): String = {
    val file = v1Dir.resolve(wdlSourceFileName)
    val tDoc = parseAndTypeCheck(file)
    val evaluator =
      Eval(evalPaths,
           Some(wdlTools.syntax.WdlVersion.V1),
           Vector.empty,
           evalFileResolver,
           Logger.Quiet)
    val elts: Vector[TAT.DocumentElement] = tDoc.elements
    elts.nonEmpty shouldBe true
    val task = tDoc.elements.head.asInstanceOf[TAT.Task]
    val ctx = evaluator.applyPrivateVariables(task.privateVariables, WdlValueBindings.empty)
    evaluator.applyCommand(task.command, ctx)
  }

  it should "evaluate command with leading spaces" in {
    val command = evalCommand("leading_spaces.wdl")
    command shouldBe "        cat <<-EOF > test.py\na = 1\nif a == 0:\n    print(\"a = 0\")\nelse:\n    print(\"a = 1\")\nEOF\n        python3 test.py"
  }

  it should "evaluate command with leading spaces2" in {
    val command = evalCommand("leading_spaces2.wdl")
    command shouldBe "    cat <<-EOF > test.py\na = 1\nif a == 0:\n    print(\"a = 0\")\nelse:\n    print(\"a = 1\")\nEOF\n    python3 test.py"
  }

  it should "evaluate command with empty lines" in {
    val command = evalCommand("empty_lines.wdl")
    command shouldBe "        cat <<-EOF > test.py\na = 1\n\n\n\nif a == 0:\n\n    print(\"a = 0\")\nelse:\n    print(\"a = 1\")\nEOF\n        python3 test.py"
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
    val file = v1Dir.resolve("bad_coercion.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    assertThrows[EvalException] {
      val _ = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)
    }
  }

  it should "handle null and optionals" taggedAs Edge in {
    val file = v1Dir.resolve("conditionals3.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val results = evaluator.applyPrivateVariables(decls, WdlValueBindings(Map("i2" -> V_Null)))
    results("powers10") shouldBe V_Array(
        Vector(V_Optional(V_Int(1)), V_Null, V_Optional(V_Int(100)))
    )
  }

  it should "handle accessing pair values" in {
    val file = v1Dir.resolve("pair.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val results = evaluator.applyPrivateVariables(
        decls,
        WdlValueBindings(
            Map(
                "p1.right" -> V_Pair(V_Int(1), V_Int(2)),
                "p2.right.left" -> V_Int(3)
            )
        )
    )
    results("i") shouldBe V_Int(1)
    results("j") shouldBe V_Int(3)
    results("k") shouldBe V_Int(5)
  }

  it should "handle empty stdout/stderr" in {
    val file = v1Dir.resolve("empty_stdout.wdl")
    parseAndTypeCheck(file)
  }

  it should "evaluate nested placeholders" in {
    val file = v1Dir.resolve("nested_placeholders.wdl")
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(file)
    val nullResults = evaluator.applyPrivateVariables(decls, WdlValueBindings(Map("s" -> V_Null)))
    nullResults.get("result") shouldBe Some(V_String("null"))
    val arrayResults = evaluator.applyPrivateVariables(
        decls,
        WdlValueBindings(Map("s" -> V_Array(V_String("hello"), V_String("world"))))
    )
    arrayResults.get("result") shouldBe Some(V_String("hello world"))
  }

  it should "evalConst" in {
    val allExpectedResults = Map(
        "flag" -> Some(WdlValues.V_Boolean(true)),
        "i" -> Some(WdlValues.V_Int(8)),
        "x" -> Some(WdlValues.V_Float(2.718)),
        "s" -> Some(WdlValues.V_String("hello world")),
        "ar1" -> Some(
            WdlValues.V_Array(
                WdlValues.V_String("A"),
                WdlValues.V_String("B"),
                WdlValues.V_String("C")
            )
        ),
        "m1" -> Some(
            WdlValues.V_Map(
                WdlValues.V_String("X") -> WdlValues.V_Int(1),
                WdlValues.V_String("Y") -> WdlValues.V_Int(10)
            )
        ),
        "p" -> Some(WdlValues.V_Pair(WdlValues.V_Int(1), WdlValues.V_Int(12))),
        "j" -> Some(WdlValues.V_Int(8)),
        "k" -> None,
        "s2" -> Some(WdlValues.V_String("hello world")),
        "readme" -> None,
        "file2" -> None
    )

    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(v1Dir.resolve("constants.wdl"))

    decls.foreach {
      case TAT.PrivateVariable(id, wdlType, expr, _) =>
        val expected: Option[WdlValues.V] = allExpectedResults(id)
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
    val (evaluator, decls) = parseAndTypeCheckAndGetDeclarations(v1Dir.resolve("bad_protocol.wdl"))
    decls match {
      case Vector(TAT.PrivateVariable(_, wdlType, expr, _)) =>
        assertThrows[EvalException] {
          evaluator.applyConstAndCoerce(expr, wdlType)
        }
      case other => throw new Exception(s"unexpected decl ${other}")
    }
  }

  it should "evaluate concatenation of different types v1" in {
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(v1Dir.resolve("add_int_and_string.wdl"))
    val results = evaluator.applyPrivateVariables(
        decls,
        WdlValueBindings(Map("subset_n" -> V_Int(2), "subset_total" -> V_Int(5)))
    )
    results("subset_param1") shouldBe V_String("-q 2/5")
    results("subset_param2") shouldBe V_String("-q 2/5")
    results("subset_param3") shouldBe V_String("-q 2/5")
  }

  it should "evaluate concatenation of different types v2" in {
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(v2Dir.resolve("add_int_and_string.wdl"), WdlVersion.V2)
    val results = evaluator.applyPrivateVariables(
        decls,
        WdlValueBindings(Map("subset_n" -> V_Int(2), "subset_total" -> V_Int(5)))
    )
    results("subset_param1") shouldBe V_String("-q 2/5")
    results("subset_param2") shouldBe V_String("-q 2/5")
  }

  it should "evaluate select_first with None argument" in {
    val expr = TAT.ExprApply(
        "select_first",
        T_Function1("select_first", T_Array(T_Int, nonEmpty = true), T_Int),
        Vector(
            TAT.ExprArray(
                Vector(TAT.ExprIdentifier("x", T_Optional(T_Int), SourceLocation.empty),
                       TAT.ValueInt(5, T_Int, SourceLocation.empty)),
                T_Array(T_Optional(T_Int), nonEmpty = true),
                SourceLocation.empty
            )
        ),
        T_Int,
        SourceLocation.empty
    )
    val evaluator = createEvaluator()
    val result = evaluator.applyExpr(expr, WdlValueBindings(Map("x" -> V_Null)))
    result shouldBe V_Int(5)
  }

  it should "evaluate a task with an unset optional input" in {
    val tDoc = parseAndTypeCheck(v1Dir.resolve("bwa_mem.wdl"))
    val task = tDoc.elements match {
      case Vector(task: TAT.Task) => task
      case _                      => throw new AssertionError("expected task")
    }
    val evaluator = createEvaluator()
    val fastq1 = evalPaths.getRootDir(ensureExists = true).resolve("fastq1.gz")
    FileUtils.writeFileContent(fastq1, "fastq1")
    val fastq2 = evalPaths.getRootDir(ensureExists = true).resolve("fastq2.gz")
    FileUtils.writeFileContent(fastq2, "fastq2")
    val index = evalPaths.getRootDir(ensureExists = true).resolve("genome_index.tar.gz")
    FileUtils.writeFileContent(index, "index")
    val inputs: Map[String, V] = Map(
        "sample_name" -> V_String("sample"),
        "fastq1_gz" -> V_File(fastq1.toString),
        "fastq2_gz" -> V_File(fastq2.toString),
        "genome_index_tgz" -> V_File(index.toString),
        "read_group" -> V_Null,
        "disk_gb" -> V_Null
    )
    val env: Map[String, V] =
      task.privateVariables.foldLeft(inputs) {
        case (env, TAT.PrivateVariable(name, wdlType, expr, _)) =>
          val wdlValue =
            evaluator.applyExprAndCoerce(expr, wdlType, WdlValueBindings(env))
          env + (name -> wdlValue)
      }
    env("actual_disk_gb") shouldBe V_Int(1)
  }

  it should "evaluate a command with a line continuation before a missing optional placeholder" in {
    val tDoc = parseAndTypeCheck(v1Dir.resolve("dangling.wdl"))
    val task = tDoc.elements match {
      case Vector(task: TAT.Task) => task
      case _                      => throw new AssertionError("expected task")
    }
    val evaluator = createEvaluator()
    val result =
      evaluator.applyCommand(
          task.command,
          WdlValueBindings(Map("a" -> WdlValues.V_String("a"), "b" -> WdlValues.V_Null))
      )
    result shouldBe
      """echo a \
        |  
        |echo "hello"""".stripMargin
  }

  it should "evaluate functions in v1.1" in {
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(v1_1Dir.resolve("functions.wdl"), WdlVersion.V1_1)
    val results = evaluator.applyPrivateVariables(decls, WdlValueBindings.empty)
    results("a") shouldBe V_Int(1)
    results("b") shouldBe V_Int(2)
    results("c") shouldBe V_Float(1)
    results("d") shouldBe V_Float(2)
    results("e") shouldBe V_Float(1.0)
    results("punzip") shouldBe V_Pair(V_Array(V_Int(1), V_Int(3)), V_Array(V_Int(2), V_Int(4)))
    results("pzip") shouldBe V_Array(V_Pair(V_Int(1), V_Int(2)), V_Pair(V_Int(3), V_Int(4)))
    results("mpairs") shouldBe V_Array(V_Pair(V_String("a"), V_Int(1)),
                                       V_Pair(V_String("b"), V_Int(2)))
    results("mmap") shouldBe V_Map(V_String("a") -> V_Int(1), V_String("b") -> V_Int(2))
    results("mkeys") shouldBe V_Array(V_String("a"), V_String("b"))
    results("acollect") shouldBe V_Map(V_String("a") -> V_Array(V_Int(1), V_Int(2)),
                                       V_String("b") -> V_Array(V_Int(3)))
    results("suf") shouldBe V_Array(V_String("hello world"), V_String("goodbye world"))
    results("dquoted") shouldBe V_Array(V_String("\"1\""), V_String("\"2\""))
    results("squoted") shouldBe V_Array(V_String("'true'"), V_String("'false'"))
    results("sepd") shouldBe V_String("1.0,2.0")
  }

  it should "allow coercion from String to Int with read_lines" in {
    val (evaluator, decls) =
      parseAndTypeCheckAndGetDeclarations(v1_1Dir.resolve("read_lines.wdl"), WdlVersion.V1_1)
    val tempFile = File.createTempFile("ints", ".txt")
    tempFile.deleteOnExit()
    FileUtils.writeFileContent(tempFile.toPath, "1\n2\n3")
    val results = evaluator
      .applyPrivateVariables(decls, WdlValueBindings(Map("f" -> V_File(tempFile.getAbsolutePath))))
    results.get("i") shouldBe Some(V_Array(V_Int(1), V_Int(2), V_Int(3)))
  }

  it should "evaluate call expression" in {
    val tDoc = parseAndTypeCheck(execDir.resolve("inputs_with_defaults.wdl"))
    val calls = tDoc.workflow.get.body.collect {
      case call: TAT.Call => call
    }
    calls.size shouldBe 1
    val call = calls.head
    val evaluator = createEvaluator()
    val env = Map(
        "row.left" -> (T_String, V_String("str1")),
        "RG_LB" -> (T_String, V_String("dataset_dataset_str1"))
    )
    evaluator.applyExprAndCoerce(call.inputs("rg"), T_String, Eval.createBindingsFromEnv(env))
  }

  it should "handle escape sequences" in {
    val tDoc = parseAndTypeCheck(v1_1Dir.resolve("escape_sequences.wdl"))
    val task = tDoc.elements match {
      case Vector(task: TAT.Task) => task
      case _                      => throw new Exception("expected a single task")
    }
    val evaluator = createEvaluator()
    val bindings = evaluator.applyPrivateVariables(task.privateVariables)
    val command = evaluator.applyCommand(task.command, bindings)
    val (_, stdout, _) = SysUtils.execCommand(command)
    stdout shouldBe "1^I1$\n2\\t2$\n3\\^I3$\n4\\\\t4$\n"
    FileUtils.writeFileContent(evaluator.paths.getStdoutFile(), stdout)
    val outputExpr = task.outputs.head.expr
    evaluator.applyExpr(outputExpr) shouldBe V_String("1^I1$\n2\\t2$\n3\\^I3$\n4\\\\t4$")
  }
}
