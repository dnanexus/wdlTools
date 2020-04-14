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
// line: line number
// col : column number
// URL:  original file or web URL
//
case class TextSource(line: Int, col: Int, url: Option[URL] = None) {
  override def toString: String = {
    if (url.isDefined) {
      s"line ${line} col ${col} of ${url}"
    } else {
      s"line ${line} col ${col}"
    }
  }
}

// Syntax error exception
class SyntaxException private (ex: Exception) extends Exception(ex) {
  def this(msg: String, text: TextSource) =
    this(new Exception(s"${msg} in file ${text.url} line ${text.line} col ${text.col}"))
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
