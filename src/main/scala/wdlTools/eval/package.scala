package wdlTools.eval

import java.nio.file.{Path, Paths}
import java.nio.charset.Charset

import wdlTools.syntax.TextSource
import wdlTools.util.{FileAccessProtocol, FileSource, FileSourceResolver, Logger}

import scala.io.Codec

case class Context(bindings: Map[String, WdlValues.V]) {
  def addBinding(name: String, value: WdlValues.V): Context = {
    assert(!(bindings contains name))
    this.copy(bindings = bindings + (name -> value))
  }
}

// There is a standard library implementation for each WDL version.
trait StandardLibraryImpl {
  def call(funcName: String, args: Vector[WdlValues.V], text: TextSource): WdlValues.V
}

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
case class EvalConfig(homeDir: Path,
                      tmpDir: Path,
                      stdout: Path,
                      stderr: Path,
                      fileResolver: FileSourceResolver,
                      encoding: Charset)

object EvalConfig {
  // Always add the default protocols (file and web) into the configuration.
  def make(homeDir: Path,
           tmpDir: Path,
           stdout: Path,
           stderr: Path,
           userSearchPath: Vector[Path] = Vector.empty,
           userProtos: Vector[FileAccessProtocol] = Vector.empty,
           encoding: Charset = Codec.default.charSet,
           logger: Logger = Logger.Quiet): EvalConfig = {
    new EvalConfig(
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
    make(devNull, devNull, devNull, devNull)
  }
}

// A runtime error
final class EvalException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSource: FileSource) = {
    this(EvalException.formatMessage(msg, text, docSource))
  }
}

object EvalException {
  def formatMessage(msg: String, text: TextSource, docSource: FileSource): String = {
    s"${msg} at ${text} in ${docSource}"
  }
}

// an error that occurs during (de)serialization of JSON
final class JsonSerializationException(message: String) extends Exception(message)
