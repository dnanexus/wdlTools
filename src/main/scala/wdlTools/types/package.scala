package wdlTools.types

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.TextSource
import wdlTools.util.{Options, TypeCheckingRegime, Verbosity}

/**
  * @param typeChecking strictness of type-checking
  */
case class TypeOptions(override val localDirectories: Vector[Path] = Vector.empty,
                       override val verbosity: Verbosity.Verbosity = Verbosity.Normal,
                       override val antlr4Trace: Boolean = false,
                       typeChecking: TypeCheckingRegime.Value = TypeCheckingRegime.Moderate,
                       errorAsException: Boolean = false)
    extends Options(localDirectories, followImports = true, verbosity, antlr4Trace)

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
