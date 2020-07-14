package wdlTools.exec

import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}

import wdlTools.eval.EvalConfig
import wdlTools.syntax.SourceLocation
import wdlTools.util.{FileAccessProtocol, FileSourceResolver, FileUtils, Logger}

import scala.io.Codec

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

class ExecPaths(
    // home directory in which the command executes
    homeDir: Path,
    // temporary file dir
    tmpDir: Path,
    // command stdout
    stdout: Path,
    // command stderr
    stderr: Path,
    // bash script
    val commandScript: Path,
    // Status code returned from the shell command
    val returnCode: Path,
    // bash script for running the docker image
    val dockerRunScript: Path,
    // the docker container name
    val dockerCid: Path,
    fileResolver: FileSourceResolver,
    encoding: Charset = FileUtils.DefaultEncoding
) extends EvalConfig(homeDir, tmpDir, stdout, stderr, fileResolver, encoding)

object ExecPaths {
  def create(
      homeDir: Path,
      tmpDir: Path,
      stdout: Path,
      stderr: Path,
      commandScript: Path,
      returnCode: Path,
      dockerRunScript: Path,
      dockerCid: Path,
      userSearchPath: Vector[Path] = Vector.empty,
      userProtos: Vector[FileAccessProtocol] = Vector.empty,
      encoding: Charset = Codec.default.charSet,
      logger: Logger = Logger.Quiet
  ): ExecPaths = {
    new ExecPaths(
        homeDir,
        tmpDir,
        stdout,
        stderr,
        commandScript,
        returnCode,
        dockerRunScript,
        dockerCid,
        FileSourceResolver.create(Vector(homeDir) ++ userSearchPath, userProtos, logger, encoding),
        encoding
    )
  }

  def createFromDir(path: Path = Paths.get("."),
                    userSearchPath: Vector[Path] = Vector.empty,
                    userProtos: Vector[FileAccessProtocol] = Vector.empty,
                    encoding: Charset = Codec.default.charSet,
                    logger: Logger = Logger.Quiet): ExecPaths = {
    create(
        path.resolve("home"),
        FileUtils.tempDir,
        path.resolve("stdout"),
        path.resolve("stderr"),
        path.resolve("commandScript"),
        path.resolve("returnCode"),
        path.resolve("dockerRunScript"),
        path.resolve("dockerCid"),
        userSearchPath,
        userProtos,
        encoding,
        logger
    )
  }

  def createFromTemp(userSearchPath: Vector[Path] = Vector.empty,
                     userProtos: Vector[FileAccessProtocol] = Vector.empty,
                     encoding: Charset = Codec.default.charSet,
                     logger: Logger = Logger.Quiet): (ExecPaths, Path) = {
    val tempDir = Files.createTempDirectory("wdlTools")
    val config = create(
        tempDir.resolve("home"),
        tempDir.resolve("tmp"),
        tempDir.resolve("stdout"),
        tempDir.resolve("stderr"),
        tempDir.resolve("commandScript"),
        tempDir.resolve("returnCode"),
        tempDir.resolve("dockerRunScript"),
        tempDir.resolve("dockerCid"),
        userSearchPath,
        userProtos,
        encoding,
        logger
    )
    (config, tempDir)
  }
}
