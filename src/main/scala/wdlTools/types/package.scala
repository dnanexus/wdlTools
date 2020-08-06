package wdlTools.types

import wdlTools.syntax.SourceLocation

object TypeCheckingRegime extends Enumeration {
  type TypeCheckingRegime = Value
  val Lenient, Moderate, Strict = Value
}

final case class TypeError(loc: SourceLocation, reason: String)

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(TypeException.formatMessage(msg, loc))
  }
  def this(errors: Seq[TypeError]) = {
    this(TypeException.formatMessageFromErrorList(errors))
  }
}

object TypeException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }

  def formatMessageFromErrorList(errors: Seq[TypeError]): String = {
    // make one big report on all the type errors
    val messages = errors.map {
      case TypeError(locSource, msg) => s"${msg} at ${locSource}"
    }
    messages.mkString("\n")
  }
}

final class SubstitutionException(message: String) extends Exception(message)

final class TypeUnificationException(message: String) extends Exception(message)

final class StdlibFunctionException(message: String) extends Exception(message)

final class DuplicateDeclarationException(message: String) extends Exception(message)
