package wdlTools.types

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.TextSource
import wdlTools.util.{Options, TypeCheckingRegime, Verbosity}

/**
  * @param typeChecking strictness of type-checking
  */
case class TypeOptions(localDirectories: Vector[Path] = Vector.empty,
                       followImports: Boolean = true,
                       verbosity: Verbosity.Verbosity = Verbosity.Normal,
                       antlr4Trace: Boolean = false,
                       typeChecking: TypeCheckingRegime.Value = TypeCheckingRegime.Moderate,
                       errorAsException: Boolean = false)
    extends Options

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceUrl: Option[URL] = None) = {
    this(TypeException.formatMessage(msg, text, docSourceUrl))
  }
}

object TypeException {
  def formatMessage(msg: String, text: TextSource, docSourceUrl: Option[URL]): String = {
    val urlPart = docSourceUrl.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}

final class TypeUnificationException(message: String) extends Exception(message)
