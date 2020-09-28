package wdlTools.exec

import java.nio.file.{Files, Path}

import spray.json.{JsNumber, JsObject, JsString, JsValue}
import wdlTools.generators.Renderer
import wdlTools.util.{FileUtils, Logger, SysUtils, errorMessage}

sealed trait TaskExecutorResult {
  def toJson: Map[String, JsValue]
}
case class TaskExecutorSuccess(returnCode: Int, outputs: JsObject, stdout: String, stderr: String)
    extends TaskExecutorResult {
  override def toJson: Map[String, JsValue] = {
    Map("status" -> JsString("success"),
        "returnCode" -> JsNumber(returnCode),
        "outputs" -> outputs,
        "stdout" -> JsString(stdout),
        "stderr" -> JsString(stderr))
  }
}
case class TaskExecutorCommandFailure(returnCode: Int, stdout: String, stderr: String)
    extends TaskExecutorResult {
  override def toJson: Map[String, JsValue] = {
    Map("status" -> JsString("failure"),
        "returnCode" -> JsNumber(returnCode),
        "stdout" -> JsString(stdout),
        "stderr" -> JsString(stderr))
  }
}
case class TaskExecutorInternalError(message: String, error: Option[Throwable] = None)
    extends TaskExecutorResult {
  override def toJson: Map[String, JsValue] = {
    Map(
        "status" -> JsString("error"),
        "errorMessage" -> JsString(errorMessage(message, error))
    )
  }
}

case class TaskCommandFileGenerator(logger: Logger = Logger.get) {
  private val renderer = Renderer()

  // Write the core bash script into a file.
  def writeCommandScript(command: Option[String],
                         hostPaths: ExecPaths,
                         guestPaths: Option[ExecPaths] = None): Path = {
    val guest = guestPaths.getOrElse(hostPaths)
    val script = command match {
      case None =>
        renderer.render(TaskCommandFileGenerator.DefaultEmptyCommandScript,
                        Map("returnCodeFile" -> guest.getReturnCodeFile().toString))
      case Some(cmd) =>
        renderer.render(
            TaskCommandFileGenerator.DefaultCommandScript,
            Map(
                "command" -> cmd,
                "homeDir" -> guest.getHomeDir().toString,
                "tempDir" -> guest.getTempDir().toString,
                "returnCodeFile" -> guest.getReturnCodeFile().toString,
                "stdoutFile" -> guest.getStdoutFile().toString,
                "stderrFile" -> guest.getStderrFile().toString
            )
        )
    }
    val commandFile = hostPaths.getCommandFile(true)
    logger.traceLimited(s"writing bash script to ${commandFile}")
    FileUtils.writeFileContent(commandFile, script, makeExecutable = true)
    commandFile
  }

  def writeDockerRunScript(imageName: String,
                           hostPaths: ExecPaths,
                           guestPaths: ExecPaths,
                           maxMemory: Long = SysUtils.availableMemory): Path = {
    val dockerRunScript = renderer.render(
        TaskCommandFileGenerator.DefaultDockerRunScript,
        Map(
            "hostRootDir" -> hostPaths.getRootDir(true).toString,
            "containerIdFile" -> hostPaths.getContainerIdFile(true).toString,
            "containerRootDir" -> guestPaths.getRootDir().toString,
            "commandFile" -> guestPaths.getCommandFile().toString,
            "stdoutFile" -> guestPaths.getStdoutFile().toString,
            "stderrFile" -> guestPaths.getStderrFile().toString,
            "imageName" -> imageName,
            "maxMemory" -> maxMemory
        )
    )
    val commandFile = hostPaths.getContainerCommandFile(true)
    logger.traceLimited(s"writing docker run script to ${commandFile}")
    FileUtils.writeFileContent(commandFile, dockerRunScript, makeExecutable = true)
    commandFile
  }

  def apply(command: Option[String],
            hostPaths: ExecPaths,
            container: Option[(String, ExecPaths)] = None): Path = {
    if (container.isDefined) {
      val (containerImage, guestPaths) = container.get
      writeCommandScript(command, hostPaths, Some(guestPaths))
      writeDockerRunScript(containerImage, hostPaths, guestPaths)
    } else {
      writeCommandScript(command, hostPaths)
    }
  }
}

object TaskCommandFileGenerator {
  val DefaultEmptyCommandScript = "/templates/exec/emptyCommandScript.ssp"
  val DefaultCommandScript = "/templates/exec/commandScript.ssp"
  val DefaultDockerRunScript = "/templates/exec/dockerRunScript.ssp"
}

case class TaskExecutor(taskContext: TaskContext,
                        hostPaths: ExecPaths,
                        guestPaths: Option[ExecPaths] = None,
                        logger: Logger = Logger.get) {
  private val scriptGenerator = TaskCommandFileGenerator(logger)

  protected lazy val useContainer: Boolean = {
    guestPaths.isDefined && taskContext.containerImage.isDefined
  }

  protected def executeCommand(timeout: Option[Int] = None): TaskExecutorResult = {
    val commandFile =
      try {
        if (useContainer) {
          scriptGenerator.apply(taskContext.command,
                                hostPaths,
                                Some(taskContext.containerImage.get, guestPaths.get))
        } else {
          scriptGenerator.apply(taskContext.command, hostPaths)
        }
      } catch {
        case t: Throwable =>
          return TaskExecutorInternalError("Error writing command file", Some(t))
      }
    if (!Files.exists(commandFile)) {
      return TaskExecutorInternalError(s"Unable to write command file ${commandFile}")
    }
    logger.trace(s"Executing command file ${commandFile}")
    // execute the shell script in a child job - this call will only fail on timeout
    val (retcode, stdout, stderr) =
      SysUtils.execScript(commandFile, timeout, logger, exceptionOnFailure = false)
    if (taskContext.runtime.isValidReturnCode(retcode)) {
      TaskExecutorSuccess(retcode, taskContext.jsonOutputs, stdout, stderr)
    } else {
      TaskExecutorCommandFailure(retcode, stdout, stderr)
    }
  }

  lazy val run: TaskExecutorResult = executeCommand()

  lazy val summary: JsObject = {
    val inputSummary = taskContext.summary
    val pathsSummary = Map("paths" -> JsObject(hostPaths.toJson()))
    val resultSummary = run.toJson
    val containerSummary = if (useContainer) {
      Map(
          "container" -> JsObject(
              Map(
                  "type" -> JsString("docker"),
                  "image" -> JsString(taskContext.containerImage.get),
                  "paths" -> JsObject(guestPaths.get.toJson(onlyExisting = false))
              )
          ),
          "commandStdout" -> JsString(
              FileUtils.readFileContent(hostPaths.getStdoutFile(), mustExist = false)
          ),
          "commandStderr" -> JsString(
              FileUtils.readFileContent(hostPaths.getStderrFile(), mustExist = false)
          )
      )
    } else {
      Map.empty
    }
    JsObject(inputSummary ++ pathsSummary ++ resultSummary ++ containerSummary)
  }
}
