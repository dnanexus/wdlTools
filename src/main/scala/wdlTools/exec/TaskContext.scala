package wdlTools.exec

import java.nio.file.{FileAlreadyExistsException, Files, Path}

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.eval.{Eval, Runtime, VBindings, WdlValueBindings, WdlValueSerde, WdlValues}
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.util.{AddressableFileSource, Bindings, FileSourceResolver, Logger}

trait LocalizationDisambiguator {
  def getLocalPath(fileSource: AddressableFileSource): Path
}

/**
  * Localizes a file according to the rules in the spec:
  * https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#task-input-localization.
  * * two input files with the same name must be located separately, to avoid name collision
  * * two input files that originated in the same storage directory must also be localized into
  *   the same directory for task execution
  * We use the general strategy of creating randomly named directories under root. We use a single
  * directory if possible, but create additional directories to avoid name collision.
  * @param rootDir the root dir - files are localize to subdirectories under this directory
  * @param existingPaths optional Set of paths that should be assumed to already exist locally
  * @param subdirPrefix prefix to add to localization dirs
  * @param disambiguationDirLimit max number of disambiguation subdirs that can be created
  */
case class SafeLocalizationDisambiguator(rootDir: Path,
                                         existingPaths: Set[Path] = Set.empty,
                                         subdirPrefix: String = "input",
                                         disambiguationDirLimit: Int = 200)
    extends LocalizationDisambiguator {
  private lazy val primaryDir = Files.createTempDirectory(rootDir, subdirPrefix)
  // mapping from source file parent directories to local directories - this
  // ensures that files that were originally from the same directory are
  // localized to the same target directory
  private var sourceToTarget: Map[String, Path] = Map.empty
  // keep track of which disambiguation dirs we've created
  private var disambiguationDirs: Set[Path] = Set(primaryDir)
  // keep track of which Paths we've returned so we can detect collisions
  private var localizedPaths: Set[Path] = existingPaths

  def getLocalizedPaths: Set[Path] = localizedPaths

  private def exists(path: Path): Boolean = {
    if (localizedPaths.contains(path)) {
      true
    } else if (Files.exists(path)) {
      localizedPaths += path
      true
    } else {
      false
    }
  }

  override def getLocalPath(source: AddressableFileSource): Path = {
    val sourceFolder = source.folder
    val localPath = sourceToTarget.get(sourceFolder) match {
      // if we already saw another file from the same source folder as `source`, try to
      // put `source` in that same target directory
      case Some(parent) if exists(parent.resolve(source.name)) =>
        throw new FileAlreadyExistsException(
            s"Trying to localize ${source} to ${parent} but the file already exists in that directory"
        )
      case Some(parent) =>
        parent.resolve(source.name)
      case None =>
        val primaryPath = primaryDir.resolve(source.name)
        if (!exists(primaryPath)) {
          sourceToTarget += (sourceFolder -> primaryDir)
          primaryPath
        } else if (disambiguationDirs.size >= disambiguationDirLimit) {
          throw new Exception(
              s"""|Tried to localize ${source} to local filesystem at ${rootDir}/*/${source.name}, 
                  |but there was a name collision and there are already the maximum number of 
                  |disambiguation directories (${disambiguationDirLimit}).""".stripMargin
                .replaceAll("\n", " ")
          )
        } else {
          // there is a name collision in primaryDir - create a new dir
          val newDir = Files.createTempDirectory(rootDir, "input")
          // we should never get a collision according to the guarantees of
          // Files.createTempDirectory, but we check anyway
          if (Files.exists(newDir) || disambiguationDirs.contains(newDir)) {
            throw new Exception(s"collision with existing dir ${newDir}")
          }
          disambiguationDirs += newDir
          sourceToTarget += (sourceFolder -> newDir)
          newDir.resolve(source.name)
        }
    }
    localizedPaths += localPath
    localPath
  }
}

case class TaskContext(task: Task,
                       inputBindings: Bindings[String, V],
                       hostEvaluator: Eval,
                       guestEvaluator: Option[Eval] = None,
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
      val disambiguator = SafeLocalizationDisambiguator(hostEvaluator.paths.getRootDir(true))
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
    Runtime.fromTask(task, hostEvaluator, Some(evalBindings), defaultRuntimeValues)

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
        Some(dockerUtils.getImage(v, runtime.getSourceLocation(Runtime.ContainerKey)))
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
    val inputs = taskIO.inputsFromJson(jsInputs, hostEvaluator, strict)
    TaskContext(task, inputs, hostEvaluator, guestEvaluator, defaultRuntimeValues, taskIO)
  }
}
