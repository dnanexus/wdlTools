package wdlTools.eval

import dx.util.{EvalPaths, FileSourceResolver, FileUtils, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{Parsers, WdlVersion}

import java.nio.file.{Path, Paths}
import wdlTools.types.{TypeCheckingRegime, TypeInfer, TypedAbstractSyntax => TAT}

class RuntimeTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Normal
  private val v1_1Dir = Paths.get(getClass.getResource("/eval/v1.1").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(v1_1Dir))
  private val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val typeInfer = TypeInfer(regime = TypeCheckingRegime.Lenient)
  private val evalPaths: EvalPaths = DefaultEvalPaths.createFromTemp()
  private val evalFileResolver =
    FileSourceResolver.create(Vector(evalPaths.getWorkDir().asJavaPath))

  private def parseAndTypeCheck(file: Path): TAT.Document = {
    val doc = parsers.parseDocument(fileResolver.fromPath(FileUtils.absolutePath(file)))
    val (tDoc, _) = typeInfer.apply(doc)
    tDoc
  }

  private def createEvaluator(wdlVersion: WdlVersion,
                              allowNonstandardCoercions: Boolean = false): Eval = {
    Eval(evalPaths,
         Some(wdlVersion),
         Vector.empty,
         evalFileResolver,
         Logger.Quiet,
         allowNonstandardCoercions)
  }

  it should "evaluate a runtime with as a default runtime" in {
    val doc = parseAndTypeCheck(v1_1Dir.resolve("apps_1177_native_default_instance.wdl"))
    val tasks = doc.elements.collect {
      case task: TAT.Task => task
    }
    tasks.size shouldBe 1
    val eval = createEvaluator(WdlVersion.V1_1)
    tasks.foreach { task =>
      val runtime = Runtime.create(task.runtime, eval)
      runtime.getClass shouldBe classOf[DefaultRuntime]
      runtime.isDefaultSystemRequirements shouldBe (true)
    }
  }

  it should "evaluate a runtime with as a custom runtime" in {
    val doc = parseAndTypeCheck(v1_1Dir.resolve("apps_1177_native_memory_override.wdl"))
    val tasks = doc.elements.collect {
      case task: TAT.Task => task
    }
    tasks.size shouldBe 1
    val eval = createEvaluator(WdlVersion.V1_1)
    tasks.foreach { task =>
      val runtime = Runtime.create(task.runtime, eval)
      runtime.getClass shouldBe classOf[DefaultRuntime]
      runtime.isDefaultSystemRequirements shouldBe (false)
    }
  }
}
