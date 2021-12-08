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
}
