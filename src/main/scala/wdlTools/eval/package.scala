package wdlTools.eval

import java.nio.file.Files
import wdlTools.syntax.SourceLocation
import dx.util.{BaseEvalPaths, FileUtils, PosixPath}

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
class DefaultEvalPaths(rootDir: PosixPath, tempDir: PosixPath) extends BaseEvalPaths {
  def getRootDir(ensureExists: Boolean = false): PosixPath = {
    getOrCreateDir("root", rootDir, ensureExists)
  }

  def getTempDir(ensureExists: Boolean = false): PosixPath = {
    getOrCreateDir("temp", tempDir, ensureExists)
  }

  /**
    * The execution directory - used as the base dir for relative paths (e.g. for glob search).
    */
  def getWorkDir(ensureExists: Boolean = false): PosixPath = {
    getOrCreateDir(DefaultEvalPaths.DefaultWorkDir,
                   getRootDir(ensureExists).resolve(DefaultEvalPaths.DefaultWorkDir),
                   ensureExists)
  }

  def getMetaDir(ensureExists: Boolean = false): PosixPath = {
    getOrCreateDir(DefaultEvalPaths.DefaultMetaDir,
                   getRootDir(ensureExists).resolve(DefaultEvalPaths.DefaultMetaDir),
                   ensureExists)
  }

  /**
    * The file that has a copy of standard output.
    */
  def getStdoutFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultEvalPaths.DefaultStdout)
  }

  /**
    * The file that has a copy of standard error.
    */
  def getStderrFile(ensureParentExists: Boolean = false): PosixPath = {
    getMetaDir(ensureParentExists).resolve(DefaultEvalPaths.DefaultStderr)
  }
}

object DefaultEvalPaths {
  val DefaultWorkDir = "work"
  val DefaultTempDir = "tmp"
  val DefaultMetaDir = "meta"
  val DefaultStdout = "stdout"
  val DefaultStderr = "stderr"

  def apply(rootDir: PosixPath, tempDir: PosixPath): DefaultEvalPaths = {
    new DefaultEvalPaths(rootDir, tempDir)
  }

  def create(): DefaultEvalPaths = {
    DefaultEvalPaths(PosixPath(FileUtils.cwd(absolute = true).toString),
                     PosixPath(FileUtils.systemTempDir.toString))
  }

  def createFromTemp(): DefaultEvalPaths = {
    val rootDir = PosixPath(Files.createTempDirectory("eval").toString)
    val tempDir = rootDir.resolve(DefaultTempDir)
    DefaultEvalPaths(rootDir, tempDir)
  }

  // an EvalConfig where all the paths point to /dev/null - only useful for
  // testing where there are no I/O functions used
  val NullPath: PosixPath = PosixPath("/dev/null")
  lazy val empty: DefaultEvalPaths = DefaultEvalPaths(NullPath, NullPath)
}

// A runtime error
final class EvalException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(EvalException.formatMessage(msg, loc))
  }

  def this(msg: String, loc: SourceLocation, cause: Throwable) = {
    this(EvalException.formatMessage(msg, loc))
    initCause(cause)
  }
}

object EvalException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }
}
