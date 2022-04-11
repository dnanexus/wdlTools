package wdlTools.exec

import dx.util.{EvalPaths, FileSourceResolver, FileUtils, Logger}
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.eval.{DefaultEvalPaths, Eval}
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.types.{TypeCheckingRegime, TypeInfer, TypedAbstractSyntax => TAT}

import java.nio.file.{Path, Paths}
import scala.collection.immutable.SeqMap

class ExecTest extends AnyFlatSpec with Matchers with Inside {
  private val execDir = Paths.get(getClass.getResource("/exec").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(execDir))
  private val logger = Logger.Normal
  private val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val typeInfer = TypeInfer(regime = TypeCheckingRegime.Lenient)
  private val evalPaths: EvalPaths = DefaultEvalPaths.createFromTemp()
  private val evalFileResolver =
    FileSourceResolver.create(Vector(evalPaths.getWorkDir().asJavaPath))
  private val evaluator =
    Eval(evalPaths, Some(WdlVersion.V1), Vector.empty, evalFileResolver, Logger.Quiet)

  def parseAndTypeCheck(file: Path): TAT.Document = {
    val doc = parsers.parseDocument(fileResolver.fromPath(FileUtils.absolutePath(file)))
    val (tDoc, _) = typeInfer.apply(doc)
    tDoc
  }

  it should "evaluate inputs" in {
    val doc = parseAndTypeCheck(execDir.resolve("inputs_with_defaults.wdl"))
    val wf = doc.workflow.getOrElse(
        throw new AssertionError("expected workflow")
    )
    val inputValues = Map(
        "dataset" -> V_String("dataset"),
        "manifest" -> V_Array(V_Pair(V_String("str1"), V_Pair(V_String("str2"), V_String("str3"))))
    )
    val values = InputOutput.inputsFromValues(wf.name,
                                              wf.inputs,
                                              inputValues,
                                              evaluator,
                                              ignoreDefaultEvalError = false,
                                              nullCollectionAsEmpty = true)
    values("row") shouldBe V_Pair(V_String("str1"), V_Pair(V_String("str2"), V_String("str3")))
  }

  it should "evaluate task input struct with optional element" in {
    val doc = parseAndTypeCheck(execDir.resolve("task_input_struct_optional_element.wdl"))
    // val wf = doc.workflow.getOrElse(
    //     None
    // )
    val task = doc.elements.collectFirst({ case t: TAT.Task => t }).get
    val inputValues = Map(
        "database" -> V_Object(SeqMap("num_rows" -> V_Int(5), "num_rows_extra" -> V_Int(5)))
    )
    val values = InputOutput.inputsFromValues(task.name,
                                              task.inputs,
                                              inputValues,
                                              evaluator,
                                              ignoreDefaultEvalError = false,
                                              nullCollectionAsEmpty = true)
    values("database") shouldBe V_Struct("MyStructDB",
                                         "num_columns" -> V_Null,
                                         "num_rows" -> V_Int(5))
  }

  it should "evaluate workflow input struct with optional element" in {
    val doc = parseAndTypeCheck(execDir.resolve("workflow_input_struct_optional_element.wdl"))
    val wf = doc.workflow.getOrElse(
        throw new ExecException("expected workflow")
    )
    val inputValues = Map(
        "database" -> V_Object(SeqMap("num_rows" -> V_Int(5), "num_rows_extra" -> V_Int(5)))
    )
    val values = InputOutput.inputsFromValues(wf.name,
                                              wf.inputs,
                                              inputValues,
                                              evaluator,
                                              ignoreDefaultEvalError = false,
                                              nullCollectionAsEmpty = true)
    values("database") shouldBe V_Struct("MyStructDB",
                                         "database_name" -> V_Null,
                                         "num_columns" -> V_Null,
                                         "num_rows" -> V_Int(5),
                                         "value_types" -> V_Null)
  }
}
