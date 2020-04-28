package wdlTools.eval

import java.net.URL
import wdlTools.syntax.TextSource

// Syntax error exception
final class EvaluationException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceURL: Option[URL] = None) = {
    this(EvaluationException.formatMessage(msg, text, docSourceURL))
  }
}

object EvaluationException {
  def formatMessage(msg: String, text: TextSource, docSourceURL: Option[URL]): String = {
    val urlPart = docSourceURL.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}
