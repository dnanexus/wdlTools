package wdlTools.exec

import java.nio.file.{FileAlreadyExistsException, Files, Path}

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.eval.{Eval, EvalConfig, Runtime, Serialize, WdlValues, Context => EvalContext}
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes
import wdlTools.util.{FileSource, FileSourceResolver, Logger}

trait FileSourceLocalizer {
  def localizeFile(uri: String): Path

  def localizeDirectory(uri: String): Path
}

// Localize a file according to the rules in the spec:
// https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#task-input-localization.
// * two input files with the same name must be located separately, to avoid name collision
// * two input files that originated in the same storage directory must also be localized into
//   the same directory for task execution
// We use the general strategy of creating randomly named directories under root. We use a single
// directory if possible, but create additional directories to avoid name collision.
case class SafeFileSourceLocalizer(root: Path,
                                   fileResolver: FileSourceResolver,
                                   subdirPrefix: String = "input")
    extends FileSourceLocalizer {
  private var sourceTargetMap: Map[Path, Path] = Map.empty
  private lazy val primaryDir = Files.createTempDirectory(root, subdirPrefix)

  private def localize(source: FileSource): Path = {
    val sourceParent = source.localPath.getParent
    sourceTargetMap.get(sourceParent) match {
      // if we already saw another file from the same parent directory as `source`, try to
      // put `source` in that same directory
      case Some(parent) if Files.exists(parent.resolve(source.fileName)) =>
        throw new FileAlreadyExistsException(
            s"Trying to localize ${source} to ${parent} but the file already exists in that directory"
        )
      case Some(parent) =>
        source.localizeToDir(parent)
      case None if Files.exists(primaryDir.resolve(source.fileName)) =>
        // there is a name collision in primaryDir, so create a new dir
        val newDir = Files.createTempDirectory(root, "input")
        sourceTargetMap += (sourceParent -> newDir)
        source.localizeToDir(newDir)
      case _ =>
        sourceTargetMap += (sourceParent -> primaryDir)
        source.localizeToDir(primaryDir)
    }
  }

  override def localizeFile(uri: String): Path = {
    val fileSource = fileResolver.resolve(uri)
    localize(fileSource)
  }

  override def localizeDirectory(uri: String): Path = {
    val fileSource = fileResolver.resolveDirectory(uri)
    localize(fileSource)
  }
}

object SafeFileSourceLocalizer {
  def create(evalCfg: EvalConfig): SafeFileSourceLocalizer = {
    SafeFileSourceLocalizer(evalCfg.homeDir, evalCfg.fileResolver)
  }
}

case class TaskContext(task: Task,
                       inputContext: EvalContext,
                       defaultRuntimeValues: Map[String, WdlValues.V] = Map.empty,
                       evaluator: Eval,
                       fileLocalizer: Option[FileSourceLocalizer] = None) {
  private lazy val dockerUtils = DockerUtils(evaluator.opts, evaluator.evalCfg)
  private lazy val hasCommand: Boolean = task.command.parts.exists {
    case ValueString(s, _, _) => s.trim.nonEmpty
    case _                    => true
  }
  private lazy val evalContext: EvalContext = {
    val fullContext = evaluator.applyDeclarations(task.declarations, inputContext)
    // If there is a command to evaluate, pre-localize all the files/dirs, otherwise
    // just allow them to be localized on demand (for example, if they're required to
    // evaluate an output value expression).
    if (hasCommand) {
      val localizer = fileLocalizer.getOrElse(
          SafeFileSourceLocalizer.create(evaluator.evalCfg)
      )
      EvalContext(fullContext.bindings.map {
        case (name, V_File(uri)) =>
          val localizedPath = localizer.localizeFile(uri)
          name -> V_File(localizedPath.toString)
        case (name, V_Directory(uri)) =>
          val localizedPath = localizer.localizeDirectory(uri)
          name -> V_Directory(localizedPath.toString)
        case other => other
      })
    } else {
      fullContext
    }
  }
  lazy val runtime: Runtime =
    Runtime.fromTask(task, evalContext, evaluator, defaultRuntimeValues)

  lazy val command: Option[String] = if (hasCommand) {
    evaluator.applyCommand(task.command, evalContext) match {
      case s if s.trim.isEmpty => None
      case s                   => Some(s)
    }
  } else {
    None
  }

  lazy val summary: Map[String, JsValue] = {
    Map(
        "task" -> JsObject(
            Map(
                "name" -> JsString(task.name),
                "source" -> JsString(task.loc.toString)
            )
        ),
        "inputs" -> JsObject(Serialize.toJson(evalContext.bindings)),
        "command" -> JsString(command.getOrElse("")),
        "runtime" -> JsObject(Serialize.toJson(runtime.getAll))
    )
  }

  // Figure out if a docker image is specified. If so, return it as a string.
  lazy val containerImage: Option[String] = {
    runtime.container match {
      case v if v.isEmpty => None
      case v =>
        Some(dockerUtils.getImage(v, runtime.getSourceLocation(Runtime.Keys.Container)))
    }
  }

  lazy val outputContext: EvalContext = {
    task.outputs.foldLeft(evalContext) {
      case (accu, output) =>
        val outputValue = evaluator.applyExpr(output.expr, accu)
        accu.addBinding(output.name, outputValue)
    }
  }

  def outputs: Map[String, (WdlTypes.T, WdlValues.V)] = {
    task.outputs.map { output =>
      output.name -> (output.wdlType, outputContext.bindings(output.name))
    }.toMap
  }

  def jsonOutputs: JsObject = {
    InputOutput.taskOutputToJson(outputContext, task.name, task.outputs)
  }
}

object TaskContext {

  /**
    * Creates TaskContext from a parsed JSON object that follows the format in the spec.
    * If there are any missing values, it tries to evaluate the input declaration's default value
    * (if any).
    * @param jsInputs map of fully-qualified input names (i.e. '{task_name}.{input_name}') to JsValues
    * @param task the task
    * @param evaluator expression evaluator
    * @param logger Logger
    * @param strict whether to throw an exception if a default cannot be evaluated; if false, then any
    *               optional parameters with unspecified values and un-evaluable defaults is set to V_Null.
    * @return TaskInputs
    * throws EvalException if
    * - evaluation fails for a default expression, unless `strict = false`
    * - a required input has no value
    */
  def fromJson(jsInputs: Map[String, JsValue],
               task: Task,
               defaultRuntimeValues: Map[String, WdlValues.V] = Map.empty,
               evaluator: Eval,
               logger: Logger = Logger.Quiet,
               localizer: Option[FileSourceLocalizer] = None,
               strict: Boolean = false): TaskContext = {
    val inputs =
      InputOutput.taskInputFromJson(jsInputs, task.name, task.inputs, evaluator, logger, strict)
    TaskContext(task, inputs, defaultRuntimeValues, evaluator, localizer)
  }
}
