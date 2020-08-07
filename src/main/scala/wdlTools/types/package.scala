package wdlTools.types

import wdlTools.syntax.SourceLocation

object TypeCheckingRegime extends Enumeration {
  type TypeCheckingRegime = Value
  val Lenient, Moderate, Strict = Value
}

final case class TypeError(loc: SourceLocation, reason: String)

/**
  * Important states during expression evaluation.
  * When evaluating a declaration, we start in the Start state and can
  * advance to InString to InPlaceholder (there cannot be nested placeholders).
  * When evaluating a command block, we start in the InString state and can
  * advance to InPlaceholder.
  */
object ExprState extends Enumeration {
  type ExprState = Value
  val Start, InString, InPlaceholder = Value
}

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
