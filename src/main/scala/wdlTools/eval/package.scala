package wdlTools.eval

import java.nio.file.{Path, Paths}
import java.nio.charset.Charset

import wdlTools.syntax.SourceLocation
import wdlTools.util.{FileAccessProtocol, FileSourceResolver, FileUtils, Logger}

import scala.io.Codec

/** Configuration for expression evaluation. Some operations perform file IO.
  *
  * @param homeDir  the root directory for relative paths, and the root directory for search (glob).
  * @param tmpDir   directory for placing temporary files.
  * @param stdout   the file that has a copy of standard output. This is used in the command section.
  *                 Must resolve to a file that either exists or is creatable.
  * @param stderr   as above for standard error.
  * @param fileResolver  FileSourceResolver for accessing files. By default local-file and http protocols are provided.
  * @param encoding the encoding to use when reading files.
  */
class EvalConfig(val homeDir: Path,
                 val tmpDir: Path,
                 val stdout: Path,
                 val stderr: Path,
                 val fileResolver: FileSourceResolver,
                 val encoding: Charset = FileUtils.DefaultEncoding)

object EvalConfig {
  def apply(homeDir: Path,
            tmpDir: Path,
            stdout: Path,
            stderr: Path,
            fileResolver: FileSourceResolver,
            encoding: Charset = FileUtils.DefaultEncoding): EvalConfig = {
    new EvalConfig(homeDir, tmpDir, stdout, stderr, fileResolver, encoding)
  }

  // Always add the default protocols (file and web) into the configuration.
  def create(homeDir: Path,
             tmpDir: Path,
             stdout: Path,
             stderr: Path,
             userSearchPath: Vector[Path] = Vector.empty,
             userProtos: Vector[FileAccessProtocol] = Vector.empty,
             encoding: Charset = Codec.default.charSet,
             logger: Logger = Logger.Quiet): EvalConfig = {
    EvalConfig(
        homeDir,
        tmpDir,
        stdout,
        stderr,
        FileSourceResolver.create(Vector(homeDir) ++ userSearchPath, userProtos, logger, encoding),
        encoding
    )
  }

  // an EvalConfig where all the paths point to /dev/null - only useful for
  // testing where there are no I/O functions used
  lazy val empty: EvalConfig = {
    val devNull = Paths.get("/dev/null")
    create(devNull, devNull, devNull, devNull)
  }
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

// an error that occurs during (de)serialization of JSON
final class JsonSerializationException(message: String) extends Exception(message)
