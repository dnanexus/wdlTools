package wdlTools.exec

import java.nio.file.{Files, Path}
import dx.util.{ExecPaths, FileUtils, PosixPath}
import wdlTools.eval.DefaultEvalPaths
import wdlTools.syntax.SourceLocation

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

class DefaultExecPaths(rootDir: PosixPath, val tempDir: PosixPath, isLocal: Boolean)
    extends DefaultEvalPaths(rootDir, tempDir, isLocal)
    with ExecPaths {
  def getCommandFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultCommandScript)
  }

  def getReturnCodeFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultReturnCode)
  }

  def getContainerCommandFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultContainerRunScript)
  }

  def getContainerIdFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultExecPaths.DefaultContainerId)
  }
}

object DefaultExecPaths {
  val DefaultCommandScript = "commandScript"
  val DefaultReturnCode = "returnCode"
  val DefaultContainerRunScript = "containerRunScript"
  val DefaultContainerId = "containerId"

  def apply(executionDir: PosixPath, tempDir: PosixPath, isLocal: Boolean): ExecPaths = {
    new DefaultExecPaths(executionDir, tempDir, isLocal)
  }

  def createLocalPathsFromDir(executionDir: Path = FileUtils.cwd(absolute = true),
                              tempDir: Path = FileUtils.systemTempDir): ExecPaths = {
    if (!Files.isDirectory(executionDir)) {
      throw new ExecException(s"${executionDir} does not exist or is not a directory")
    }
    DefaultExecPaths(PosixPath(executionDir.toString), PosixPath(tempDir.toString), isLocal = true)
  }

  def createLocalPathsFromTemp(): ExecPaths = {
    val rootDir = PosixPath(Files.createTempDirectory("wdlTools").toRealPath().toString)
    val tempDir = rootDir.resolve(DefaultEvalPaths.DefaultTempDir)
    DefaultExecPaths(rootDir, tempDir, isLocal = true)
  }

  def createContainerPaths(containerExecutionDir: PosixPath,
                           containerTempDir: PosixPath = PosixPath("/tmp")): ExecPaths = {
    DefaultExecPaths(containerExecutionDir, containerTempDir, isLocal = false)
  }

  def createLocalContainerPair(
      useWorkingDir: Boolean = false,
      containerMountDir: PosixPath,
      containerTempDir: PosixPath = PosixPath("/tmp")
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
