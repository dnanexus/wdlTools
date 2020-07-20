package wdlTools.syntax.v1

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.syntax.{Comment, SourceLocation, SyntaxException, WdlVersion}
import wdlTools.syntax.v1.ConcreteSyntax._
import wdlTools.util.{FileSource, FileSourceResolver, Logger}

class ConcreteSyntaxV1Test extends AnyFlatSpec with Matchers {
  private val sourcePath = Paths.get(getClass.getResource("/syntax/v1").getPath)
  private val tasksDir = sourcePath.resolve("tasks")
  private val workflowsDir = sourcePath.resolve("workflows")
  private val structsDir = sourcePath.resolve("structs")
  private val fileResolver = FileSourceResolver.create(Vector(tasksDir, workflowsDir, structsDir))
  private val logger = Logger.Quiet

  private def getTaskSource(fname: String): FileSource = {
    fileResolver.fromPath(tasksDir.resolve(fname))
  }

  private def getWorkflowSource(fname: String): FileSource = {
    fileResolver.fromPath(workflowsDir.resolve(fname))
  }

  private def getStructSource(fname: String): FileSource = {
    fileResolver.fromPath(structsDir.resolve(fname))
  }

  private def getDocument(FileSource: FileSource): Document = {
    ParseTop(WdlV1Grammar.newInstance(FileSource, Vector.empty, logger = logger)).parseDocument
  }

  it should "handle various types" in {
    val doc = getDocument(getTaskSource("types.wdl"))

    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    val InputSection(decls, _) = task.input.get
    decls(0) should matchPattern { case Declaration("i", TypeInt(_), None, _)     => }
    decls(1) should matchPattern { case Declaration("s", TypeString(_), None, _)  => }
    decls(2) should matchPattern { case Declaration("x", TypeFloat(_), None, _)   => }
    decls(3) should matchPattern { case Declaration("b", TypeBoolean(_), None, _) => }
    decls(4) should matchPattern { case Declaration("f", TypeFile(_), None, _)    => }
    decls(5) should matchPattern {
      case Declaration("p1", TypePair(_: TypeInt, _: TypeString, _), None, _) =>
    }
    decls(6) should matchPattern {
      case Declaration("p2", TypePair(_: TypeFloat, _: TypeFile, _), None, _) =>
    }
    decls(7) should matchPattern {
      case Declaration("p3", TypePair(_: TypeBoolean, _: TypeBoolean, _), None, _) =>
    }
    decls(8) should matchPattern {
      case Declaration("ia", TypeArray(_: TypeInt, false, _), None, _) =>
    }
    decls(9) should matchPattern {
      case Declaration("sa", TypeArray(_: TypeString, false, _), None, _) =>
    }
    decls(10) should matchPattern {
      case Declaration("xa", TypeArray(_: TypeFloat, false, _), None, _) =>
    }
    decls(11) should matchPattern {
      case Declaration("ba", TypeArray(_: TypeBoolean, false, _), None, _) =>
    }
    decls(12) should matchPattern {
      case Declaration("fa", TypeArray(_: TypeFile, false, _), None, _) =>
    }
    decls(13) should matchPattern {
      case Declaration("m_si", TypeMap(_: TypeInt, _: TypeString, _), None, _) =>
    }
    decls(14) should matchPattern {
      case Declaration("m_ff", TypeMap(_: TypeFile, _: TypeFile, _), None, _) =>
    }
    decls(15) should matchPattern {
      case Declaration("m_bf", TypeMap(_: TypeBoolean, _: TypeFloat, _), None, _) =>
    }
  }

  it should "handle types and expressions" in {
    val doc = getDocument(getTaskSource("expressions.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.declarations(0) should matchPattern {
      case Declaration("i", _: TypeInt, Some(ExprInt(3, _)), _) =>
    }
    task.declarations(1) should matchPattern {
      case Declaration("s", _: TypeString, Some(ExprString("hello world", _)), _) =>
    }
    task.declarations(2) should matchPattern {
      case Declaration("x", _: TypeFloat, Some(ExprFloat(4.3, _)), _) =>
    }
    task.declarations(3) should matchPattern {
      case Declaration("f", _: TypeFile, Some(ExprString("/dummy/x.txt", _)), _) =>
    }
    task.declarations(4) should matchPattern {
      case Declaration("b", _: TypeBoolean, Some(ExprBoolean(false, _)), _) =>
    }

    // Logical expressions
    task.declarations(5) should matchPattern {
      case Declaration("b2",
                       _: TypeBoolean,
                       Some(ExprLor(ExprBoolean(true, _), ExprBoolean(false, _), _)),
                       _) =>
    }
    task.declarations(6) should matchPattern {
      case Declaration("b3",
                       _: TypeBoolean,
                       Some(ExprLand(ExprBoolean(true, _), ExprBoolean(false, _), _)),
                       _) =>
    }
    task.declarations(7) should matchPattern {
      case Declaration("b4", _: TypeBoolean, Some(ExprEqeq(ExprInt(3, _), ExprInt(5, _), _)), _) =>
    }
    task.declarations(8) should matchPattern {
      case Declaration("b5", _: TypeBoolean, Some(ExprLt(ExprInt(4, _), ExprInt(5, _), _)), _) =>
    }
    task.declarations(9) should matchPattern {
      case Declaration("b6", _: TypeBoolean, Some(ExprGte(ExprInt(4, _), ExprInt(5, _), _)), _) =>
    }
    task.declarations(10) should matchPattern {
      case Declaration("b7", _: TypeBoolean, Some(ExprNeq(ExprInt(6, _), ExprInt(7, _), _)), _) =>
    }
    task.declarations(11) should matchPattern {
      case Declaration("b8", _: TypeBoolean, Some(ExprLte(ExprInt(6, _), ExprInt(7, _), _)), _) =>
    }
    task.declarations(12) should matchPattern {
      case Declaration("b9", _: TypeBoolean, Some(ExprGt(ExprInt(6, _), ExprInt(7, _), _)), _) =>
    }
    task.declarations(13) should matchPattern {
      case Declaration("b10", _: TypeBoolean, Some(ExprNegate(ExprIdentifier("b2", _), _)), _) =>
    }

    // Arithmetic
    task.declarations(14) should matchPattern {
      case Declaration("j", _: TypeInt, Some(ExprAdd(ExprInt(4, _), ExprInt(5, _), _)), _) =>
    }
    task.declarations(15) should matchPattern {
      case Declaration("j1", _: TypeInt, Some(ExprMod(ExprInt(4, _), ExprInt(10, _), _)), _) =>
    }
    task.declarations(16) should matchPattern {
      case Declaration("j2", _: TypeInt, Some(ExprDivide(ExprInt(10, _), ExprInt(7, _), _)), _) =>
    }
    task.declarations(17) should matchPattern {
      case Declaration("j3", _: TypeInt, Some(ExprIdentifier("j", _)), _) =>
    }
    task.declarations(18) should matchPattern {
      case Declaration("j4",
                       _: TypeInt,
                       Some(ExprAdd(ExprIdentifier("j", _), ExprInt(19, _), _)),
                       _) =>
    }

    task.declarations(19) should matchPattern {
      case Declaration(
          "ia",
          TypeArray(_: TypeInt, false, _),
          Some(ExprArrayLiteral(Vector(ExprInt(1, _), ExprInt(2, _), ExprInt(3, _)), _)),
          _
          ) =>
    }
    task.declarations(20) should matchPattern {
      case Declaration("ia",
                       TypeArray(_: TypeInt, true, _),
                       Some(ExprArrayLiteral(Vector(ExprInt(10, _)), _)),
                       _) =>
    }
    task.declarations(21) should matchPattern {
      case Declaration("k",
                       _: TypeInt,
                       Some(ExprAt(ExprIdentifier("ia", _), ExprInt(3, _), _)),
                       _) =>
    }
    task.declarations(22) should matchPattern {
      case Declaration(
          "k2",
          _: TypeInt,
          Some(ExprApply("f", Vector(ExprInt(1, _), ExprInt(2, _), ExprInt(3, _)), _)),
          _
          ) =>
    }

    task.declarations(23) should matchPattern {
      case Declaration("m", TypeMap(TypeInt(_), TypeString(_), _), Some(ExprMapLiteral(_, _)), _) =>
    }
    /*    val m = task.declarations(23).expr
    m should matchPattern {
      case Map(ExprInt(1, _) -> ExprString("a", _),
                                      ExprInt(2, _) -> ExprString("b", _)),
    _)),*/

    task.declarations(24) should matchPattern {
      case Declaration("k3",
                       _: TypeInt,
                       Some(ExprIfThenElse(ExprBoolean(true, _), ExprInt(1, _), ExprInt(2, _), _)),
                       _) =>
    }
    task.declarations(25) should matchPattern {
      case Declaration("k4", _: TypeInt, Some(ExprGetName(ExprIdentifier("x", _), "a", _)), _) =>
    }

    task.declarations(26) should matchPattern {
      case Declaration("o", _: TypeObject, Some(ExprObjectLiteral(_, _)), _) =>
    }
    /*(Map("A" -> ExprInt(1, _),
     "B" -> ExprInt(2, _,
     _))), _)) */

    task.declarations(27) should matchPattern {
      case Declaration("twenty_threes",
                       TypePair(TypeInt(_), TypeString(_), _),
                       Some(ExprPair(ExprInt(23, _), ExprString("twenty-three", _), _)),
                       _) =>
    }
  }

  it should "handle get name" in {
    val doc = getDocument(getTaskSource("get_name_bug.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe "district"
    task.input shouldBe None
    task.output shouldBe None
    task.command should matchPattern {
      case CommandSection(Vector(), _) =>
    }
    task.meta shouldBe None
    task.parameterMeta shouldBe None

    task.declarations(0) should matchPattern {
      case Declaration("k", TypeInt(_), Some(ExprGetName(ExprIdentifier("x", _), "a", _)), _) =>
    }
  }

  it should "detect a wrong comment style" in {
    assertThrows[Exception] {
      getDocument(getTaskSource("wrong_comment_style.wdl"))
    }
  }

  it should "parse a task with an output section only" in {
    val doc = getDocument(getTaskSource("output_section.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe "wc"
    task.output.get should matchPattern {
      case OutputSection(Vector(Declaration("num_lines", TypeInt(_), Some(ExprInt(3, _)), _)), _) =>
    }
  }

  it should "parse a task" in {
    val doc = getDocument(getTaskSource("wc.wdl"))

    doc.comments(1) should matchPattern {
      case Comment("# A task that counts how many lines a file has",
                   SourceLocation(_, 1, 0, 1, 46)) =>
    }
    doc.comments(8) should matchPattern {
      case Comment("# Just a random declaration", SourceLocation(_, 8, 2, 8, 29)) =>
    }
    doc.comments(11) should matchPattern {
      case Comment("# comment after bracket", SourceLocation(_, 11, 12, 11, 35)) =>
    }
    doc.comments(12) should matchPattern {
      case Comment("# Int num_lines = read_int(stdout())", SourceLocation(_, 12, 4, 12, 40)) =>
    }
    doc.comments(13) should matchPattern {
      case Comment("# end-of-line comment", SourceLocation(_, 13, 23, 13, 44)) =>
    }
    doc.comments(20) should matchPattern {
      case Comment("# The comment below is empty", SourceLocation(_, 20, 4, 20, 32)) =>
    }
    doc.comments(21) should matchPattern {
      case Comment("#", SourceLocation(_, 21, 4, 21, 5)) =>
    }

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe "wc"
    task.input.get should matchPattern {
      case InputSection(Vector(Declaration("inp_file", _: TypeFile, None, _)), _) =>
    }
    task.output.get should matchPattern {
      case OutputSection(Vector(Declaration("num_lines", _: TypeInt, Some(ExprInt(3, _)), _)), _) =>
    }
    task.command shouldBe a[CommandSection]
    task.command.parts.size shouldBe 3
    task.command.parts(0) should matchPattern {
      case ExprString("\n    # this is inside the command and so not a WDL comment\n    wc -l ",
                      _) =>
    }
    task.command.parts(1) should matchPattern {
      case ExprIdentifier("inp_file", _) =>
    }

    task.meta.get shouldBe a[MetaSection]
    task.meta.get.kvs.size shouldBe 1
    val mkv = task.meta.get.kvs.head
    mkv should matchPattern {
      case MetaKV("author", MetaValueString("Robin Hood", _), _) =>
    }

    task.parameterMeta.get shouldBe a[ParameterMetaSection]
    task.parameterMeta.get.kvs.size shouldBe 1
    val mpkv = task.parameterMeta.get.kvs.head
    mpkv should matchPattern {
      case MetaKV("inp_file", MetaValueString("just because", _), _) =>
    }

    task.declarations(0) should matchPattern {
      case Declaration("i", TypeInt(_), Some(ExprAdd(ExprInt(4, _), ExprInt(5, _), _)), _) =>
    }
  }

  it should "detect when a task section appears twice" in {
    assertThrows[Exception] {
      getDocument(getTaskSource("multiple_input_section.wdl"))
    }
  }

  it should "handle string interpolation" in {
    val doc = getDocument(getTaskSource("interpolation.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.name shouldBe "foo"
    task.input.get shouldBe a[InputSection]
    task.input.get.declarations.size shouldBe 2
    task.input.get.declarations(0) should matchPattern {
      case Declaration("min_std_max_min", TypeInt(_), None, _) =>
    }
    task.input.get.declarations(1) should matchPattern {
      case Declaration("prefix", TypeString(_), None, _) =>
    }

    task.command shouldBe a[CommandSection]
    task.command.parts(0) should matchPattern {
      case ExprString("\n    echo ", _) =>
    }
    task.command.parts(1) should matchPattern {
      case ExprPlaceholderSep(ExprString(",", _), ExprIdentifier("min_std_max_min", _), _) =>
    }
  }

  it should "parse structs" in {
    val doc = getDocument(getStructSource("I.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    val structs = doc.elements.collect {
      case x: TypeStruct => x
    }
    structs.size shouldBe 2
    structs(0).name shouldBe "Address"
    structs(0).members.size shouldBe 3
    structs(0).members should matchPattern {
      case Vector(StructMember("street", TypeString(_), _),
                  StructMember("city", TypeString(_), _),
                  StructMember("zipcode", TypeInt(_), _)) =>
    }

    structs(1).name shouldBe "Data"
    structs(1).members should matchPattern {
      case Vector(StructMember("history", TypeFile(_), _),
                  StructMember("date", TypeInt(_), _),
                  StructMember("month", TypeString(_), _)) =>
    }
  }

  it should "parse a simple workflow" in {
    val doc = getDocument(getWorkflowSource("I.wdl"))
    doc.elements.size shouldBe 0

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.name shouldBe "biz"
    wf.body.size shouldBe 3

    val calls = wf.body.collect {
      case x: Call => x
    }
    calls.size shouldBe 1
    calls(0) should matchPattern {
      case Call("bar", Some(CallAlias("boz", _)), _, _) =>
    }
    calls(0).inputs.get.value should matchPattern {
      case Vector(CallInput("i", ExprIdentifier("s", _), _)) =>
    }

    val scatters = wf.body.collect {
      case x: Scatter => x
    }
    scatters.size shouldBe 1
    scatters(0).identifier shouldBe "i"
    scatters(0).expr should matchPattern {
      case ExprArrayLiteral(Vector(ExprInt(1, _), ExprInt(2, _), ExprInt(3, _)), _) =>
    }
    scatters(0).body.size shouldBe 1
    scatters(0).body(0) should matchPattern {
      case Call("add", None, _, _) =>
    }
    val call = scatters(0).body(0).asInstanceOf[Call]
    call.inputs.get.value should matchPattern {
      case Vector(CallInput("x", ExprIdentifier("i", _), _)) =>
    }

    val conditionals = wf.body.collect {
      case x: Conditional => x
    }
    conditionals.size shouldBe 1
    conditionals(0).expr should matchPattern {
      case ExprEqeq(ExprBoolean(true, _), ExprBoolean(false, _), _) =>
    }
    conditionals(0).body should matchPattern {
      case Vector(Call("sub", None, _, _)) =>
    }
    conditionals(0).body(0).asInstanceOf[Call].inputs.size shouldBe 0

    wf.meta.get shouldBe a[MetaSection]
    wf.meta.get.kvs should matchPattern {
      case Vector(MetaKV("author", MetaValueString("Robert Heinlein", _), _)) =>
    }
  }

  it should "handle import statements" in {
    val doc = getDocument(getWorkflowSource("imports.wdl"))

    doc.version.value shouldBe WdlVersion.V1

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe 2

    doc.workflow should not be empty

    val calls: Vector[Call] = doc.workflow.get.body.collect {
      case call: Call => call
    }
    calls(0).name shouldBe "I.biz"
    calls(1).name shouldBe "I.undefined"
    calls(2).name shouldBe "wc"
  }

  it should "correctly report an error" in {
    assertThrows[Exception] {
      val _ = getDocument(getWorkflowSource("bad_declaration.wdl"))
    }
  }

  it should "handle chained operations" in {
    val doc = getDocument(getTaskSource("bug16-chained-operations.wdl"))

    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    task.declarations.size shouldBe 1
    val decl = task.declarations.head
    decl.name shouldBe "j"
    decl.expr.get should matchPattern {
      case ExprAdd(ExprAdd(ExprIdentifier("i", _), ExprIdentifier("i", _), _),
                   ExprIdentifier("i", _),
                   _) =>
    }
  }

  it should "handle chained operations in a workflow" in {
    val doc = getDocument(getWorkflowSource("chained_expr.wdl"))
    doc.elements.size shouldBe 0

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.body.size shouldBe 1
    val decl = wf.body.head.asInstanceOf[Declaration]
    decl.name shouldBe "b"
    decl.expr.get should matchPattern {
      case ExprAdd(ExprAdd(ExprInt(1, _), ExprInt(2, _), _), ExprInt(3, _), _) =>
    }
  }

  it should "have real world GATK tasks" in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/tasks/JointGenotypingTasks.wdl"
    val FileSource = fileResolver.resolve(url)
    val doc = getDocument(FileSource)

    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 19
  }

  it should "be able to handle GATK workflow" in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping.wdl"
    val FileSource = fileResolver.resolve(url)
    val doc = getDocument(FileSource)

    doc.version.value shouldBe WdlVersion.V1
  }

  it should "handle compound expression" in {
    val doc = getDocument(getWorkflowSource("compound_expr_bug.wdl"))
    doc.elements.size shouldBe 0

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.body.size shouldBe 1
    val decl = wf.body.head.asInstanceOf[Declaration]
    decl.name shouldBe "a"
    decl.expr.get should matchPattern {
      case ExprApply("select_first",
                     Vector(
                         ExprArrayLiteral(Vector(ExprInt(3, _),
                                                 ExprApply("round", Vector(ExprInt(100, _)), _)),
                                          _)
                     ),
                     _) =>
        ()
    }
  }

  it should "handle call with extra comma" in {
    val doc = getDocument(getWorkflowSource("call_bug.wdl"))
    doc.elements.size shouldBe 1

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]
  }

  it should "version is a reserved keyword" in {
    assertThrows[Exception] {
      val _ = getDocument(getWorkflowSource("call_bug2.wdl"))
    }
  }

  it should "relative imports" in {
    val doc = getDocument(getWorkflowSource("relative_imports.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.body.size shouldBe 1
    val call = wf.body.head.asInstanceOf[Call]
    call.name shouldBe "library.wc"
  }

  it should "relative imports II" in {
    val doc = getDocument(getWorkflowSource("relative_imports_II.wdl"))

    doc.version.value shouldBe WdlVersion.V1
    val wf = doc.workflow.get
    wf shouldBe a[Workflow]

    wf.body.size shouldBe 1
    val call = wf.body.head.asInstanceOf[Call]
    call.name shouldBe "cd.count_dogs"
  }
  it should "parse a two-level workflow" in {
    getDocument(getWorkflowSource("two_level.wdl"))
  }

  it should "parse a task with meta keys that conflict with keywords" in {
    val doc = getDocument(getWorkflowSource("meta_key_conflicts.wdl"))
    doc.elements.size shouldBe 2
    val task = doc.elements.head match {
      case t: Task => t
      case _       => throw new Exception("Expected a single task")
    }
    task.meta shouldBe defined
    val metaKvs = task.meta.get.kvs
    metaKvs.size shouldBe 1
    metaKvs.head should matchPattern {
      case MetaKV("version", MetaValueString("1.1", _), _) =>
    }
    task.parameterMeta shouldBe defined
    val paramMetaKvs = task.parameterMeta.get.kvs
    paramMetaKvs.size shouldBe 1
    paramMetaKvs(0) should matchPattern {
      case MetaKV(
          "i",
          MetaValueObject(Vector(
                              MetaKV("description", MetaValueString("An int", _), _),
                              MetaKV("default", MetaValueInt(3, _), _),
                              MetaKV("array_of_objs",
                                     MetaValueArray(
                                         Vector(
                                             MetaValueObject(
                                                 Vector(
                                                     MetaKV("foo", MetaValueBoolean(false, _), _),
                                                     MetaKV("bar", MetaValueFloat(1.0, _), _)
                                                 ),
                                                 _
                                             )
                                         ),
                                         _
                                     ),
                                     _)
                          ),
                          _),
          _
          ) =>
    }
  }

  it should "parse task with meta array of objects" in {
    getDocument(getTaskSource("suggestions_meta.wdl"))
  }

  it should "parse workflow with empty call input" in {
    getDocument(getWorkflowSource("empty_call_input.wdl"))
  }

  it should "allow trailing comma" in {
    val doc = getDocument(getWorkflowSource("extra_comma.wdl"))
    doc.workflow shouldBe defined
    val wf = doc.workflow.get
    wf.body.size shouldBe 4
    wf.body(0) should matchPattern {
      case Declaration(
          "a",
          TypeArray(TypeInt(_), false, _),
          Some(ExprArrayLiteral(Vector(ExprInt(1, _), ExprInt(2, _), ExprInt(3, _)), _)),
          _
          ) =>
    }
    wf.body(1) should matchPattern {
      case Declaration(
          "m",
          TypeMap(TypeString(_), TypeInt(_), _),
          Some(
              ExprMapLiteral(
                  Vector(ExprMember(ExprString("hello", _), ExprInt(1, _), _)),
                  _
              )
          ),
          _
          ) =>
    }
    wf.body(2) should matchPattern {
      case Declaration(
          "obj",
          TypeObject(_),
          Some(
              ExprObjectLiteral(
                  Vector(ExprMember(ExprString("foo", _), ExprInt(2, _), _)),
                  _
              )
          ),
          _
          ) =>
    }
    wf.body(3) should matchPattern {
      case Call("baz",
                None,
                Some(CallInputs(Vector(CallInput("s", ExprString("hi", _), _)), _)),
                _) =>
    }

    wf.meta shouldBe defined
    val meta = wf.meta.get.kvs
    meta.size shouldBe 5
    meta should matchPattern {
      case Vector(
          MetaKV("x", MetaValueString("'", _), _),
          MetaKV("y", MetaValueString("\"", _), _),
          MetaKV("foo", MetaValueObject(Vector(MetaKV("bar", MetaValueInt(1, _), _)), _), _),
          MetaKV("baz",
                 MetaValueArray(Vector(MetaValueInt(1, _), MetaValueInt(2, _), MetaValueInt(3, _)),
                                _),
                 _),
          MetaKV("blorf", MetaValueArray(Vector(), _), _)
          ) =>
    }
  }

  it should "report bad types" in {
    assertThrows[SyntaxException] {
      getDocument(getWorkflowSource("bad_type.wdl"))
    }
  }

  it should "report unterminated double quotes" taggedAs Edge in {
    assertThrows[SyntaxException] {
      getDocument(getWorkflowSource("unterminated_dquote.wdl"))
    }
  }
}
