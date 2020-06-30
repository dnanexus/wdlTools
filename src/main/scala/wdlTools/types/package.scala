package wdlTools.types

import wdlTools.syntax.TextSource
import wdlTools.util.{FileSource, FileSourceResolver, Logger, Options}

object TypeCheckingRegime extends Enumeration {
  type TypeCheckingRegime = Value
  val Strict, Moderate, Lenient = Value
}

/**
  * @param typeChecking             strictness of type-checking
  * @param allowNonWorkflowInputs   allow missing inputs; these can be provided at
  *                                 runtime by the user
  */
case class TypeOptions(fileResolver: FileSourceResolver = FileSourceResolver.create(),
                       followImports: Boolean = true,
                       logger: Logger = Logger.Normal,
                       antlr4Trace: Boolean = false,
                       typeChecking: TypeCheckingRegime.TypeCheckingRegime =
                         TypeCheckingRegime.Moderate,
                       allowNonWorkflowInputs: Boolean = true)
    extends Options

final case class TypeError(docSource: FileSource, textSource: TextSource, reason: String)

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSource: FileSource) = {
    this(TypeException.formatMessage(msg, text, docSource))
  }
  def this(errors: Seq[TypeError]) = {
    this(TypeException.formatMessageFromErrorList(errors))
  }
}

object TypeException {
  def formatMessage(msg: String, text: TextSource, docSource: FileSource): String = {
    s"${msg} at ${text} in ${docSource}"
  }

  def formatMessageFromErrorList(errors: Seq[TypeError]): String = {
    // make one big report on all the type errors
    val messages = errors.map {
      case TypeError(docSource, textSource, msg) => s"${msg} in ${docSource} at ${textSource}"
    }
    messages.mkString("\n")
  }
}

final class SubstitutionException(message: String) extends Exception(message)

final class TypeUnificationException(message: String) extends Exception(message)

final class StdlibFunctionException(message: String) extends Exception(message)

final class DuplicateDeclarationException(message: String) extends Exception(message)
