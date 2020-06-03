package wdlTools.types

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.TextSource
import wdlTools.util.{Options, TypeCheckingRegime, Verbosity}

/**
  * @param typeChecking             strictness of type-checking
  * @param allowNonWorkflowInputs   allow missing inputs; these can be provided at
  *                                 runtime by the user
  */
case class TypeOptions(localDirectories: Vector[Path] = Vector.empty,
                       followImports: Boolean = true,
                       verbosity: Verbosity.Verbosity = Verbosity.Normal,
                       antlr4Trace: Boolean = false,
                       typeChecking: TypeCheckingRegime.Value = TypeCheckingRegime.Moderate,
                       allowNonWorkflowInputs: Boolean = true)
    extends Options

final case class TypeError(docSourceUrl: Option[URL], textSource: TextSource, reason: String)

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceUrl: Option[URL] = None) = {
    this(TypeException.formatMessage(msg, text, docSourceUrl))
  }
  def this(errors: Seq[TypeError]) = {
    this(TypeException.formatMessageFromErrorList(errors))
  }
}

object TypeException {
  def formatMessage(msg: String, text: TextSource, docSourceUrl: Option[URL]): String = {
    val urlPart = docSourceUrl.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }

  def formatMessageFromErrorList(errors: Seq[TypeError]): String = {
    // make one big report on all the type errors
    val messages = errors.map {
      case TypeError(docSourceUrl, textSource, msg) =>
        val urlPart = docSourceUrl.map(url => s" in ${url.toString}").getOrElse("")
        s"${msg} at ${urlPart} ${textSource}"
    }
    messages.mkString("\n")
  }
}

final class SubstitutionException(message: String) extends Exception(message)

final class TypeUnificationException(message: String) extends Exception(message)

final class StdlibFunctionException(message: String) extends Exception(message)

final class DuplicateDeclarationException(message: String) extends Exception(message)
