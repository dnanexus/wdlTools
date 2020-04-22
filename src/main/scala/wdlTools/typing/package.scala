package wdlTools.typing

import java.net.URL

import wdlTools.syntax.TextSource

// Type error exception
final class TypeException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceURL: Option[URL] = None) =
    this(s"${msg} at ${text}${docSourceURL.map(url => s" in ${url.toString}").getOrElse("")}")
}

final class TypeUnificationException(message: String) extends Exception(message)
