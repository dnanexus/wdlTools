package wdlTools.exec

import java.nio.file.Files

import spray.json.{JsNumber, JsObject, JsString, JsValue}
import wdlTools.generators.Renderer
import wdlTools.util.{FileUtils, Logger, SysUtils, Util => UUtil}

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
        "errorMessage" -> JsString(UUtil.errorMessage(message, error))
    )
  }
}

abstract class TaskExecutor(
    taskContext: TaskContext,
    execPaths: ExecPaths,
    logger: Logger
) {
  private val renderer = Renderer()

  // Write the core bash script into a file.
  protected def writeCommandScript(command: Option[String]): Unit = {
    val script = command match {
      case None =>
        renderer.render(TaskExecutor.DEFAULT_EMPTY_COMMAND_SCRIPT, Map("paths" -> execPaths))
      case Some(cmd) =>
        renderer.render(TaskExecutor.DEFAULT_COMMAND_SCRIPT,
                        Map("command" -> cmd, "paths" -> execPaths))
    }
    logger.traceLimited(s"writing bash script to ${execPaths.commandScript}")
    FileUtils.writeFileContent(execPaths.commandScript, script, makeExecutable = true)
  }

  protected def writeTaskCommandScript(): Unit = {
    writeCommandScript(taskContext.command)
  }

  protected def writeDockerRunScript(imageName: String,
                                     maxMemory: Long = SysUtils.availableMemory): Unit = {
    val dockerRunScript = renderer.render(
        TaskExecutor.DEFAULT_DOCKER_RUN_SCRIPT,
        Map(
            "paths" -> execPaths,
            "imageName" -> imageName,
            "maxMemory" -> maxMemory
        )
    )
    logger.traceLimited(s"writing docker run script to ${execPaths.dockerRunScript}")
    FileUtils.writeFileContent(execPaths.dockerRunScript, dockerRunScript, makeExecutable = true)
  }

  protected def writeTaskDockerRunScript(maxMemory: Long = SysUtils.availableMemory): Unit = {
    taskContext.containerImage match {
      case None            => ()
      case Some(imageName) => writeDockerRunScript(imageName, maxMemory)
    }
  }
}

object TaskExecutor {
  val DEFAULT_EMPTY_COMMAND_SCRIPT = "/templates/exec/emptyCommandScript.ssp"
  val DEFAULT_COMMAND_SCRIPT = "/templates/exec/commandScript.ssp"
  val DEFAULT_DOCKER_RUN_SCRIPT = "/templates/exec/dockerRunScript.ssp"
}

case class DefaultTaskExecutor(taskContext: TaskContext,
                               execPaths: ExecPaths,
                               logger: Logger,
                               useContainer: Boolean = true)
    extends TaskExecutor(taskContext, execPaths, logger) {
  protected def executeCommand(timeout: Option[Int] = None): TaskExecutorResult = {
    val script =
      try {
        writeTaskCommandScript()
        if (useContainer) {
          writeTaskDockerRunScript()
          execPaths.dockerRunScript
        } else {
          execPaths.commandScript
        }
      } catch {
        case t: Throwable =>
          return TaskExecutorInternalError("Error writing execution script", Some(t))
      }
    if (!Files.exists(script)) {
      return TaskExecutorInternalError(s"Unable to write execution script ${script}")
    }
    logger.trace(s"Executing bash script ${script}")
    // execute the shell script in a child job - this call will only fail on timeout
    val (retcode, stdout, stderr) =
      SysUtils.execScript(script, timeout, logger, exceptionOnFailure = false)
    if (taskContext.runtime.isValidReturnCode(retcode)) {
      TaskExecutorSuccess(retcode, taskContext.jsonOutputs, stdout, stderr)
    } else {
      TaskExecutorCommandFailure(retcode, stdout, stderr)
    }
  }

  lazy val run: TaskExecutorResult = executeCommand()

  lazy val summary: JsObject = {
    val inputSummary = taskContext.summary
    val resultSummary = run.toJson
    val containerSummary = if (useContainer && taskContext.containerImage.isDefined) {
      Map(
          "container" -> JsObject(
              Map(
                  "type" -> JsString("docker"),
                  "image" -> JsString(taskContext.containerImage.get)
              )
          )
      )
    } else {
      Map.empty
    }
    JsObject(inputSummary ++ containerSummary ++ resultSummary)
  }
}
