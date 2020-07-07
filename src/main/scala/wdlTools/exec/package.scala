package wdlTools.exec

import wdlTools.syntax.SourceLocation

// A runtime error
final class ExecException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(ExecException.formatMessage(msg, loc))
  }
}

object ExecException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }
}
