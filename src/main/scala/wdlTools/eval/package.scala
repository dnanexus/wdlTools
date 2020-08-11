package wdlTools.eval

import java.nio.file.{Files, Path}

import wdlTools.syntax.SourceLocation
import wdlTools.util.FileUtils

/**
  * Paths configuration for evaluation of IO-related expressions. The general structure of an
  * evaluation directory is:
  *
  * root
  * |_home
  * |_temp
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
class EvalPaths(rootDir: Path, tempDir: Path) {
  private var cache: Map[String, Path] = Map.empty

  protected def getOrCreateDir(key: String, path: Path, ensureExists: Boolean): Path = {
    cache.getOrElse(key, if (ensureExists) {
      val resolved = FileUtils.createDirectories(path)
      cache += (key -> resolved)
      resolved
    } else {
      path
    })
  }

  def getRootDir(ensureExists: Boolean = false): Path =
    getOrCreateDir("root", rootDir, ensureExists)

  def getTempDir(ensureExists: Boolean = false): Path =
    getOrCreateDir("temp", tempDir, ensureExists)

  /**
    * The execution directory - used as the base dir for relative paths (e.g. for glob search).
    */
  def getHomeDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir(EvalPaths.DefaultHomeDir,
                   getRootDir(ensureExists).resolve(EvalPaths.DefaultHomeDir),
                   ensureExists)
  }

  def getMetaDir(ensureExists: Boolean = false): Path = {
    getOrCreateDir(EvalPaths.DefaultMetaDir,
                   getRootDir(ensureExists).resolve(EvalPaths.DefaultMetaDir),
                   ensureExists)
  }

  /**
    * The file that has a copy of standard output.
    */
  def getStdoutFile(ensureParentExists: Boolean = false): Path =
    getMetaDir(ensureParentExists).resolve(EvalPaths.DefaultStdout)

  /**
    * The file that has a copy of standard error.
    */
  def getStderrFile(ensureParentExists: Boolean = false): Path =
    getMetaDir(ensureParentExists).resolve(EvalPaths.DefaultStderr)
}

object EvalPaths {
  val DefaultHomeDir = "home"
  val DefaultTempDir = "tmp"
  val DefaultMetaDir = "meta"
  val DefaultStdout = "stdout"
  val DefaultStderr = "stderr"

  def apply(rootDir: Path, tempDir: Path): EvalPaths = {
    new EvalPaths(rootDir, tempDir)
  }

  def create(): EvalPaths = {
    EvalPaths(FileUtils.cwd, FileUtils.systemTempDir)
  }

  def createFromTemp(): EvalPaths = {
    val rootDir = Files.createTempDirectory("eval")
    val tempDir = rootDir.resolve(DefaultTempDir)
    EvalPaths(rootDir, tempDir)
  }

  // an EvalConfig where all the paths point to /dev/null - only useful for
  // testing where there are no I/O functions used
  lazy val empty: EvalPaths = EvalPaths(FileUtils.NullPath, FileUtils.NullPath)
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
