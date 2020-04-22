package wdlTools.typing

import java.net.URL

import wdlTools.syntax.TextSource

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceURL: Option[URL] = None) = {
    this(TypeException.formatMessage(msg, text, docSourceURL))
  }
}

object TypeException {
  def formatMessage(msg: String, text: TextSource, docSourceURL: Option[URL]): String = {
    val urlPart = docSourceURL.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}

final class TypeUnificationException(message: String) extends Exception(message)
