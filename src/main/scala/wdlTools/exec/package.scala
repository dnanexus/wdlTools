package wdlTools.exec

import java.nio.file.{Files, Path, Paths}

import spray.json.{JsString, JsValue}
import wdlTools.eval.EvalPaths
import wdlTools.syntax.SourceLocation
import dx.util.FileUtils

// A runtime error
final class ExecException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(ExecException.formatMessage(msg, loc))
  }

  def this(message: String, cause: Throwable, loc: SourceLocation) = {
    this(message, loc)
    initCause(cause)
  }
}

object ExecException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }
}

class ExecPaths(rootDir: Path, tempDir: Path) extends EvalPaths(rootDir, tempDir) {
  def getCommandFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(ExecPaths.DefaultCommandScript)
  }

  def getReturnCodeFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(ExecPaths.DefaultReturnCode)
  }

  def getContainerCommandFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(ExecPaths.DefaultContainerRunScript)
  }

  def getContainerIdFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(ExecPaths.DefaultContainerId)
  }

  def toJson(onlyExisting: Boolean = true): Map[String, JsValue] = {
    Map(
        "root" -> getRootDir(),
        "work" -> getWorkDir(),
        "meta" -> getMetaDir(),
        "tmp" -> getTempDir(),
        "stdout" -> getStdoutFile(),
        "stderr" -> getStderrFile(),
        "commands" -> getCommandFile(),
        "returnCode" -> getReturnCodeFile(),
        "containerCommands" -> getContainerCommandFile(),
        "containerId" -> getContainerIdFile()
    ).flatMap {
      case (key, path) =>
        if (!onlyExisting || Files.exists(path)) {
          Some(key -> JsString(path.toString))
        } else {
          None
        }
    }
  }
}

object ExecPaths {
  val DefaultCommandScript = "commandScript"
  val DefaultReturnCode = "returnCode"
  val DefaultContainerRunScript = "containerRunScript"
  val DefaultContainerId = "containerId"

  def apply(executionDir: Path, tempDir: Path): ExecPaths = {
    new ExecPaths(executionDir, tempDir)
  }

  def createLocalPathsFromDir(executionDir: Path = FileUtils.cwd,
                              tempDir: Path = FileUtils.systemTempDir): ExecPaths = {
    if (!Files.isDirectory(executionDir)) {
      throw new ExecException(s"${executionDir} does not exist or is not a directory")
    }
    ExecPaths(executionDir, tempDir)
  }

  def createLocalPathsFromTemp(): ExecPaths = {
    val rootDir = Files.createTempDirectory("wdlTools")
    val tempDir = rootDir.resolve(EvalPaths.DefaultTempDir)
    ExecPaths(rootDir, tempDir)
  }

  def createContainerPaths(containerExecutionDir: Path,
                           containerTempDir: Path = Paths.get("/tmp")): ExecPaths = {
    ExecPaths(containerExecutionDir, containerTempDir)
  }

  def createLocalContainerPair(
      useWorkingDir: Boolean = false,
      containerMountDir: Path,
      containerTempDir: Path = Paths.get("/tmp")
  ): (ExecPaths, ExecPaths) = {
    val localPaths = if (useWorkingDir) {
      createLocalPathsFromDir()
    } else {
      createLocalPathsFromTemp()
    }
    val containerPaths = createContainerPaths(containerMountDir, containerTempDir)
    (localPaths, containerPaths)
  }
}
