package wdlTools.exec

import java.nio.file.{FileAlreadyExistsException, Files, Path}

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.eval.{Eval, Runtime, Serialize, WdlValues, Context => EvalContext}
import wdlTools.types.TypedAbstractSyntax._
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
case class SafeFileSourceLocalizer(root: Path, subdirPrefix: String = "input")
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
    val fileSource = FileSourceResolver.get.resolve(uri)
    localize(fileSource)
  }

  override def localizeDirectory(uri: String): Path = {
    val fileSource = FileSourceResolver.get.resolveDirectory(uri)
    localize(fileSource)
  }
}

case class TaskContext(task: Task,
                       inputContext: EvalContext,
                       hostEvaluator: Eval,
                       guestEvaluator: Option[Eval] = None,
                       defaultRuntimeValues: Map[String, WdlValues.V] = Map.empty,
                       fileResolver: FileSourceResolver = FileSourceResolver.get,
                       logger: Logger = Logger.get) {
  private lazy val dockerUtils = DockerUtils(fileResolver, logger)
  private lazy val hasCommand: Boolean = task.command.parts.exists {
    case ValueString(s, _, _) => s.trim.nonEmpty
    case _                    => true
  }
  // The inputs and runtime section are evaluated using the host paths
  // (which will be the same as the guest paths, unless we're running in a container)
  private lazy val evalContext: EvalContext = {
    val fullContext = hostEvaluator.applyDeclarations(task.declarations, inputContext)
    // If there is a command to evaluate, pre-localize all the files/dirs, otherwise
    // just allow them to be localized on demand (for example, if they're required to
    // evaluate an output value expression).
    if (hasCommand) {
      val localizer =
        SafeFileSourceLocalizer(hostEvaluator.paths.getRootDir(true))
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
    Runtime.fromTask(task, hostEvaluator, Some(evalContext), defaultRuntimeValues)

  // The command is evaluated using the guest paths, since it will be executed within
  // the guest system (i.e. container) if applicable, otherwise host and guest are the same
  lazy val command: Option[String] = if (hasCommand) {
    val guestEval = guestEvaluator.getOrElse(hostEvaluator)
    guestEval.applyCommand(task.command, evalContext) match {
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
                "source" -> JsString(task.loc.source.toString),
                "sourceLocation" -> JsString(task.loc.locationString)
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
        val outputValue = hostEvaluator.applyExpr(output.expr, accu)
        accu.addBinding(output.name, outputValue)
    }
  }

  def outputs: Map[String, WdlValues.V] = {
    val outputFileResolver =
      fileResolver.addToLocalSearchPath(Vector(hostEvaluator.paths.getHomeDir()))
    task.outputs.map { output =>
      val value = outputContext.bindings.get(output.name)
      val resolved: WdlValues.V =
        InputOutput.resolveWdlValue(output.name,
                                    output.wdlType,
                                    value,
                                    outputFileResolver,
                                    output.loc)
      output.name -> resolved
    }.toMap
  }

  def jsonOutputs: JsObject = {
    InputOutput.taskOutputToJson(outputs, task.name, task.outputs)
  }
}

object TaskContext {

  /**
    * Creates TaskContext from a parsed JSON object that follows the format in the spec.
    * If there are any missing values, it tries to evaluate the input declaration's default value
    * (if any).
    * @param jsInputs map of fully-qualified input names (i.e. '{task_name}.{input_name}') to JsValues
    * @param task the task
    * @param hostEvaluator expression evaluator for the guest (i.e. container) system
    * @param guestEvaluator expression evaluator for the guest (i.e. container) system - defaults to `hostEvaluator`
    * @param strict whether to throw an exception if a default cannot be evaluated; if false, then any
    *               optional parameters with unspecified values and un-evaluable defaults is set to V_Null.
    * @return TaskInputs
    * throws EvalException if
    * - evaluation fails for a default expression, unless `strict = false`
    * - a required input has no value
    */
  def fromJson(jsInputs: Map[String, JsValue],
               task: Task,
               hostEvaluator: Eval,
               guestEvaluator: Option[Eval] = None,
               defaultRuntimeValues: Map[String, WdlValues.V] = Map.empty,
               logger: Logger = Logger.Quiet,
               strict: Boolean = false): TaskContext = {
    val inputs =
      InputOutput.taskInputFromJson(jsInputs, task.name, task.inputs, hostEvaluator, logger, strict)
    TaskContext(task, inputs, hostEvaluator, guestEvaluator, defaultRuntimeValues)
  }
}
