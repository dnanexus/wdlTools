package wdlTools.exec

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.eval.{Eval, Runtime, VBindings, WdlValueBindings, WdlValueSerde, WdlValues}
import wdlTools.types.TypedAbstractSyntax._
import dx.util.{Bindings, DockerUtils, FileSourceResolver, Logger, SafeLocalizationDisambiguator}

case class TaskContext(task: Task,
                       inputBindings: Bindings[String, V],
                       hostEvaluator: Eval,
                       guestEvaluator: Option[Eval] = None,
                       overrideRuntimeValues: Option[VBindings] = None,
                       overrideHintValues: Option[VBindings] = None,
                       defaultRuntimeValues: Option[VBindings] = None,
                       taskIO: TaskInputOutput,
                       fileResolver: FileSourceResolver = FileSourceResolver.get,
                       logger: Logger = Logger.get) {
  private lazy val dockerUtils = DockerUtils(fileResolver, logger)
  private lazy val hasCommand: Boolean = task.command.parts.exists {
    case ValueString(s, _, _) => s.trim.nonEmpty
    case _                    => true
  }
  // The inputs and runtime section are evaluated using the host paths
  // (which will be the same as the guest paths, unless we're running in a container)
  private lazy val evalBindings: VBindings = {
    val bindings = hostEvaluator.applyPrivateVariables(task.privateVariables, inputBindings).toMap
    // If there is a command to evaluate, pre-localize all the files/dirs, otherwise
    // just allow them to be localized on demand (for example, if they're required to
    // evaluate an output value expression).
    WdlValueBindings(if (hasCommand) {
      val disambiguator = SafeLocalizationDisambiguator(hostEvaluator.paths.getRootDir(true))()
      // TODO: put localization behind a trait so we can swap in e.g. a parallelized implementation
      bindings.map {
        case (name, V_File(uri)) =>
          val fileSource = fileResolver.resolve(uri)
          val localizedPath = disambiguator.getLocalPath(fileSource)
          fileSource.localize(localizedPath)
          name -> V_File(localizedPath.toString)
        case (name, V_Directory(uri)) =>
          val folderSource = fileResolver.resolveDirectory(uri)
          val localizedPath = disambiguator.getLocalPath(folderSource)
          folderSource.localize(localizedPath)
          name -> V_Directory(localizedPath.toString)
        case other => other
      }
    } else {
      bindings
    })
  }
  lazy val runtime: Runtime =
    Runtime.fromTask(task,
                     hostEvaluator,
                     Some(evalBindings),
                     overrideRuntimeValues,
                     defaultRuntimeValues)

  // The command is evaluated using the guest paths, since it will be executed within
  // the guest system (i.e. container) if applicable, otherwise host and guest are the same
  lazy val command: Option[String] = if (hasCommand) {
    val guestEval = guestEvaluator.getOrElse(hostEvaluator)
    guestEval.applyCommand(task.command, evalBindings) match {
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
        "inputs" -> JsObject(WdlValueSerde.serializeBindings(evalBindings)),
        "command" -> JsString(command.getOrElse("")),
        "runtime" -> JsObject(WdlValueSerde.serializeMap(runtime.getAll))
    )
  }

  // Figure out if a docker image is specified. If so, return it as a string.
  lazy val containerImage: Option[String] = {
    runtime.container match {
      case v if v.isEmpty => None
      case v =>
        try {
          Some(dockerUtils.getImage(v))
        } catch {
          case ex: Throwable =>
            throw new ExecException(s"Error resolving container image ${v}",
                                    ex,
                                    runtime.getSourceLocation(Runtime.ContainerKey))
        }
    }
  }

  lazy val outputBindings: Bindings[String, V] = {
    val init: Bindings[String, V] = evalBindings
    task.outputs.foldLeft(init) {
      case (accu, output) =>
        val outputValue = hostEvaluator.applyExpr(output.expr, accu)
        accu.add(output.name, outputValue)
    }
  }

  def outputs: Map[String, WdlValues.V] = {
    val outputFileResolver =
      fileResolver.addToLocalSearchPath(Vector(hostEvaluator.paths.getWorkDir()))
    task.outputs.map { output =>
      val value = outputBindings.get(output.name)
      val resolved: WdlValues.V =
        TaskInputOutput.resolveOutputValue(output.name,
                                           output.wdlType,
                                           value,
                                           outputFileResolver,
                                           output.loc)
      output.name -> resolved
    }.toMap
  }

  def jsonOutputs: JsObject = {
    taskIO.outputValuesToJson(outputs)
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
    * @return TaskInputs
    * throws EvalException if
    * - evaluation fails for a default expression, unless `strict = false`
    * - a required input has no value
    */
  def fromJson(jsInputs: Map[String, JsValue],
               task: Task,
               hostEvaluator: Eval,
               guestEvaluator: Option[Eval] = None,
               defaultRuntimeValues: Option[VBindings] = None,
               taskIO: TaskInputOutput,
               strict: Boolean = false): TaskContext = {
    val (inputs, overrideRuntimeValues, overrideHintValues) =
      taskIO.inputsFromJson(jsInputs, hostEvaluator, strict)
    TaskContext(task,
                inputs,
                hostEvaluator,
                guestEvaluator,
                overrideRuntimeValues,
                overrideHintValues,
                defaultRuntimeValues,
                taskIO)
  }
}
