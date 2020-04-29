package wdlTools.syntax

import java.net.URL

sealed abstract class WdlVersion(val name: String, val order: Int) extends Ordered[WdlVersion] {
  def compare(that: WdlVersion): Int = this.order - that.order
}

object WdlVersion {
  case object Draft_2 extends WdlVersion("draft-2", 0)
  case object V1 extends WdlVersion("1.0", 1)

  val All: Vector[WdlVersion] = Vector(V1, Draft_2).sortWith(_ < _)

  def withName(name: String): WdlVersion = {
    val version = All.collectFirst { case v if v.name == name => v }
    if (version.isDefined) {
      version.get
    } else {
      throw new NoSuchElementException(s"No value found for ${name}")
    }
  }
}

/**
  * A multi-line element. Lines are 1-based, `endLine` is inclusive,
  * and `endCol` is exclusive.
  */
trait PositionRange extends Ordered[PositionRange] {
  def line: Int

  def col: Int

  def endLine: Int

  def endCol: Int

}

/** Source location in a WDL program. We add it to each syntax element
  * so we could do accurate error reporting. All positions are 1-indexed.
  *
  * Note: 'line' and 'col' may be 0 for "implicit" elements. Currently,
  * the only example of this is Version, which in draft-2 documents has
  * an implicit value of WdlVersion.Draft_2, but there is no actual version
  * statement.
  *
  * @param line: line number starting line
  * @param col: starting column
  * @param endLine: ending line, end-inclusive
  * @param endCol: ending column, end-exclusive
  */
case class TextSource(line: Int, col: Int, endLine: Int, endCol: Int) extends PositionRange {
  lazy val lineRange: Range = line to endLine

  def compare(that: PositionRange): Int = {
    line - that.line match {
      case 0 =>
        col - that.col match {
          case 0 =>
            endLine - that.endLine match {
              case 0     => endCol - that.endCol
              case other => other
            }
          case other => other
        }
      case other => other
    }
  }

  override def toString: String = {
    s"${line}:${col}-${endLine}:${endCol}"
  }
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
  * A WDL comment.
  * @param value the comment string, including prefix ('#')
  * @param text the location of the comment in the source file
  */
case class Comment(value: String, text: TextSource) extends Ordered[Comment] {
  override def compare(that: Comment): Int = text.line - that.text.line
}

case class CommentMap(comments: Map[Int, Comment]) {
  def nonEmpty: Boolean = {
    comments.nonEmpty
  }

  lazy val minLine: Int = comments.keys.min

  lazy val maxLine: Int = comments.keys.max

  def filterWithin(range: Seq[Int]): CommentMap = {
    CommentMap(comments.filterKeys(range.contains))
  }

  def toSortedVector: Vector[Comment] = {
    comments.values.toVector.sortWith(_ < _)
  }

  def get(line: Int): Option[Comment] = {
    comments.get(line)
  }

  def apply(line: Int): Comment = {
    comments(line)
  }
}

object CommentMap {
  val empty: CommentMap = CommentMap(Map.empty)
}
