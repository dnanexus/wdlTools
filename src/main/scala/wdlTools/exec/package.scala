package wdlTools.exec

import wdlTools.syntax.TextSource
import wdlTools.util.FileSource

// A runtime error
final class ExecException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSource: FileSource) = {
    this(ExecException.formatMessage(msg, text, docSource))
  }
}

object ExecException {
  def formatMessage(msg: String, text: TextSource, docSource: FileSource): String = {
    s"${msg} at ${text} in ${docSource}"
  }
}
