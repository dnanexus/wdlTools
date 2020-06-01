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

/** Configuration for expression evaluation. Some operations perform file IO.
  *
  * @param homeDir  the root directory for relative paths, and the root directory for search (glob).
  * @param tmpDir   directory for placing temporary files.
  * @param stdout   the file that has a copy of standard output. This is used in the command section.
  * @param stderr   as above for standard error.
  * @param encoding the encoding to use when reading files.
  */
case class EvalConfig(homeDir: Path,
                      tmpDir: Path,
                      stdout: Path,
                      stderr: Path,
                      encoding: Charset = Codec.default.charSet)

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
