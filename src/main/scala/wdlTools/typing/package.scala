package wdlTools.typing

import wdlTools.syntax.TextSource

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource) =
    this(s"${msg} at ${text}")
}

final class TypeUnificationException(message: String) extends Exception(message)
