package wdlTools.eval

import java.nio.file.{Files, Path}

import wdlTools.syntax.SourceLocation
import wdlTools.util.FileUtils

trait EvalPaths {
  def getRootDir(ensureExists: Boolean = false): Path

  def getTempDir(ensureExists: Boolean = false): Path

  /**
    * The execution directory - used as the base dir for relative paths (e.g. for glob search).
    */
  def getWorkDir(ensureExists: Boolean = false): Path

  def getMetaDir(ensureExists: Boolean = false): Path

  /**
    * The file that has a copy of standard output.
    */
  def getStdoutFile(ensureParentExists: Boolean = false): Path

  /**
    * The file that has a copy of standard error.
    */
  def getStderrFile(ensureParentExists: Boolean = false): Path
}

abstract class BaseEvalPaths extends EvalPaths {
  private var cache: Map[String, Path] = Map.empty

  protected def createDir(key: String, path: Path): Path = {
    val resolved = FileUtils.createDirectories(path)
    cache += (key -> resolved)
    resolved
  }

  protected def getOrCreateDir(key: String, path: Path, ensureExists: Boolean): Path = {
    val resolved = cache.getOrElse(key, if (ensureExists) createDir(key, path) else path)
    if (Files.exists(resolved)) {
      resolved.toRealPath()
    } else {
      resolved.toAbsolutePath
    }
  }
}

/**
  * Paths configuration for evaluation of IO-related expressions. The general structure of an
  * evaluation directory is:
  *
  * root
  * |_work
  * |_tmp
  * |_meta
  *   |_stdout
  *   |_stderr
  *
  * where `stdout` and `stderr` are files that store the stdout/stderr of a process. The temp
  * directory may instead be another location, such as the system tempdir.
  *
  * @param rootDir  the root directory - all other paths (except possibly tmpDir) are under this dir
  * @param tempDir  directory for placing temporary files.
  */
class DefaultEvalPaths(rootDir: Path, tempDir: Path) extends BaseEvalPaths {
  def getRootDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir("root", rootDir, ensureExists)
  }

  def getTempDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir("temp", tempDir, ensureExists)
  }

  /**
    * The execution directory - used as the base dir for relative paths (e.g. for glob search).
    */
  def getWorkDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir(DefaultEvalPaths.DefaultWorkDir,
                   getRootDir(ensureExists).resolve(DefaultEvalPaths.DefaultWorkDir),
                   ensureExists)
  }

  def getMetaDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir(DefaultEvalPaths.DefaultMetaDir,
                   getRootDir(ensureExists).resolve(DefaultEvalPaths.DefaultMetaDir),
                   ensureExists)
  }

  /**
    * The file that has a copy of standard output.
    */
  def getStdoutFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultEvalPaths.DefaultStdout)
  }

  /**
    * The file that has a copy of standard error.
    */
  def getStderrFile(ensureParentExists: Boolean = false): Path = {
    getMetaDir(ensureParentExists).resolve(DefaultEvalPaths.DefaultStderr)
  }
}

object DefaultEvalPaths {
  val DefaultWorkDir = "work"
  val DefaultTempDir = "tmp"
  val DefaultMetaDir = "meta"
  val DefaultStdout = "stdout"
  val DefaultStderr = "stderr"

  def apply(rootDir: Path, tempDir: Path): EvalPaths = {
    new DefaultEvalPaths(rootDir, tempDir)
  }

  def create(): EvalPaths = {
    DefaultEvalPaths(FileUtils.cwd, FileUtils.systemTempDir)
  }

  def createFromTemp(): EvalPaths = {
    val rootDir = Files.createTempDirectory("eval")
    val tempDir = rootDir.resolve(DefaultTempDir)
    DefaultEvalPaths(rootDir, tempDir)
  }

  // an EvalConfig where all the paths point to /dev/null - only useful for
  // testing where there are no I/O functions used
  lazy val empty: EvalPaths = DefaultEvalPaths(FileUtils.NullPath, FileUtils.NullPath)
}

// A runtime error
final class EvalException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(EvalException.formatMessage(msg, loc))
  }
}

object EvalException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }
}
