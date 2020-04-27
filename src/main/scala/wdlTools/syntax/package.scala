package wdlTools.syntax

import java.net.URL

sealed abstract class WdlVersion(val name: String, val order: Int) extends Ordered[WdlVersion] {
  def compare(that: WdlVersion): Int = this.order - that.order
}

object WdlVersion {
  case object Draft_2 extends WdlVersion("draft-2", 0)
  case object V1 extends WdlVersion("1.0", 1)

  val All: Vector[WdlVersion] = Vector(V1, Draft_2).sortWith(_ < _)

  def fromName(name: String): WdlVersion = {
    All.collectFirst { case v if v.name == name => v }.get
  }
}

// source location in a WDL program. We add it to each syntax element
// so we could do accurate error reporting.
//
// Note: 'line' and 'col' may be 0 for "implicit" elements. Currently,
// the only example of this is Version, which in draft-2 documents has
// an implicit value of WdlVersion.Draft_2, but there is no actual version
// statement.
//
// line: line number
// col : column number
// URL:  original file or web URL
//
case class TextSource(line: Int, col: Int, endLine: Int, endCol: Int) extends Ordered[TextSource] {
  override def compare(that: TextSource): Int = {
    line - that.line
  }

  override def toString: String = {
    s"${line}:${col}-${endLine}:${endCol}"
  }

  lazy val lineRange: Range = line until endLine

  lazy val maxCol: Int = Vector(col, endCol).max
}

object TextSource {
  val empty: TextSource = TextSource(0, 0, 0, 0)

  def fromSpan(start: TextSource, stop: TextSource): TextSource = {
    TextSource(
        start.line,
        start.col,
        stop.endLine,
        stop.endCol
    )
  }
}

// A syntax error that occured when parsing a document. It is generated
// by the ANTLR machinery and we transform it into this format.
final case class SyntaxError(docSourceURL : Option[URL],
                             symbol: String,
                             line: Int,
                             charPositionInLine: Int,
                             msg: String)

final class SyntaxAggregateException(message: String) extends Exception(message)

// Syntax error exception
final class SyntaxException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceURL: Option[URL] = None) = {
    this(SyntaxException.formatMessage(msg, text, docSourceURL))
  }
}

object SyntaxException {
  def formatMessage(msg: String, text: TextSource, docSourceURL: Option[URL]): String = {
    val urlPart = docSourceURL.map(url => s" in ${url.toString}").getOrElse("")
    s"${msg} at ${text}${urlPart}"
  }
}


/**
  * Type hierarchy for comments.
  *
  * # this is a line comment
  * # that's split across two lines
  * #
  * ## this is a preformatted comment
  */
abstract class Comment {}
case class CommentLine(text: String) extends Comment
case class CommentEmpty() extends Comment
case class CommentPreformatted(lines: Seq[String]) extends Comment
case class CommentCompound(comments: Seq[Comment]) extends Comment
