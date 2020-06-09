package wdlTools.eval

import java.net.URL
import java.nio.file.Path
import java.nio.charset.Charset

import wdlTools.syntax.TextSource

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

// A protocol defined by the user. Intended for allowing access to
// private/public clouds such as S3, Azure, or dnanexus. This has to be a web
// protocol, meaning that the prefix has to include "://". Anything
// else is considered a local file.
//
// PREFIX
//   For S3              s3://
//   For google cloud    gs://
//   For dnanexus        dx://
//
trait FileAccessProtocol {
  val prefixes : Vector[String]

  // Get the size of the file in bytes
  def size(path : String) : Long

  // Read the entire file into a string
  def readFile(path : String) : String
}

/** Configuration for expression evaluation. Some operations perform file IO.
  *
  * @param homeDir  the root directory for relative paths, and the root directory for search (glob).
  * @param tmpDir   directory for placing temporary files.
  * @param stdout   the file that has a copy of standard output. This is used in the command section.
  * @param stderr   as above for standard error.
  * @param protocols  protocols for accessing files. By default local-file and http protocols are provided.
  * @param encoding the encoding to use when reading files.
  */
case class EvalConfig(homeDir: Path,
                      tmpDir: Path,
                      stdout: Path,
                      stderr: Path,
                      protocols : Map[String, FileAccessProtocol],
                      encoding: Charset)

object EvalConfig {
  // Always add the default protocols (file and web) into the configuration.
  def make(homeDir: Path,
           tmpDir: Path,
           stdout: Path,
           stderr: Path,
           userProtos : Vector[FileAccessProtocol] = Vector.empty,
           encoding: Charset = Codec.default.charSet) = {
    val defaultProtos = Vector(IoSupp.LocalFiles(encoding),
                               IoSupp.HttpProtocol(encoding))
    val allProtos = defaultProtos ++ userProtos
    val dispatchTbl : Map[String, FileAccessProtocol] =
      allProtos.map {
        proto => proto.prefixes.map { prefix =>
          (prefix, proto)
        }
      }
        .flatten
        .toMap
    new EvalConfig(homeDir,
                   tmpDir,
                   stdout,
                   stderr,
                   dispatchTbl,
                   encoding)
  }
}

// A runtime error
final class EvalException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceUrl: Option[URL] = None) = {
    this(EvalException.formatMessage(msg, text, docSourceUrl))
  }
}

object EvalException {
  def formatMessage(msg: String, text: TextSource, docSourceUrl: Option[URL]): String = {
    val urlPart = docSourceUrl.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}

// an error that occurs during (de)serialization of JSON
final class JsonSerializationException(message: String) extends Exception(message)
