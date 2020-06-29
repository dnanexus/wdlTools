package wdlTools.exec

import java.net.URL

import wdlTools.syntax.TextSource

// A runtime error
final class ExecException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceUrl: Option[URL] = None) = {
    this(ExecException.formatMessage(msg, text, docSourceUrl))
  }
}

object ExecException {
  def formatMessage(msg: String, text: TextSource, docSourceUrl: Option[URL]): String = {
    val urlPart = docSourceUrl.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}
