package wdlTools.syntax

import dx.util.{FileNode, StringFileNode}

import scala.collection.immutable.TreeMap

sealed abstract class WdlVersion(val name: String, val order: Int, val aliases: Set[String])
    extends Ordered[WdlVersion] {
  def compare(that: WdlVersion): Int = this.order - that.order
}

object WdlVersion {
  case object Draft_2 extends WdlVersion("draft-2", 0, Set.empty)
  case object V1 extends WdlVersion("1.0", 1, aliases = Set("draft-3"))
  case object V1_1 extends WdlVersion("1.1", 2, Set.empty)
  case object V2 extends WdlVersion("2.0", 3, aliases = Set("development"))

  val All: Vector[WdlVersion] = Vector(V2, V1_1, V1, Draft_2).sortWith(_ < _)

  def withName(name: String): WdlVersion = {
    All
      .collectFirst {
        case v if v.name == name || v.aliases.contains(name) => v
      }
      .getOrElse(
          throw new NoSuchElementException(s"No value found for ${name}")
      )
  }

  def withNameIgnoreCase(name: String): WdlVersion = {
    val nameLc = name.toLowerCase
    All
      .collectFirst {
        case v
            if v.name.toLowerCase == nameLc || v.aliases
              .map(_.toLowerCase)
              .contains(nameLc) =>
          v
      }
      .getOrElse(
          throw new NoSuchElementException(s"No value found for ${name}")
      )
  }
}

/**
  * Source location in a WDL program. We add it to each syntax element
  * so we could do accurate error reporting. All positions are 1-indexed.
  *
  * Note: 'line' and 'col' may be 0 for "implicit" elements. Currently,
  * the only example of this is Version, which in draft-2 documents has
  * an implicit value of WdlVersion.Draft_2, but there is no actual version
  * statement.
  *
  * @param line: starting line number
  * @param col: starting column
  * @param endLine: line (end-inclusive) on which the last token ends
  * @param endCol: column (end-exclusive) at which the last token ends
  */
case class SourceLocation(source: FileNode, line: Int, col: Int, endLine: Int, endCol: Int)
    extends Ordered[SourceLocation] {
  lazy val lineRange: Range = line to endLine

  def compare(that: SourceLocation): Int = {
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

  def locationString: String = {
    s"${line}:${col}-${endLine}:${endCol}"
  }

  override def toString: String = {
    s"${locationString} in ${source}"
  }
}

object SourceLocation {
  val empty: SourceLocation = SourceLocation(StringFileNode.empty, 0, 0, 0, 0)

  def fromSpan(source: FileNode, start: SourceLocation, stop: SourceLocation): SourceLocation = {
    SourceLocation(
        source,
        start.line,
        start.col,
        stop.endLine,
        stop.endCol
    )
  }

  def merge(locs: Vector[SourceLocation]): SourceLocation = {
    val sorted = locs.sortWith(_ < _)
    val start = sorted.headOption.getOrElse(throw new Exception("cannot merge empty Vector"))
    val stop = locs.last
    SourceLocation(
        start.source,
        start.line,
        start.col,
        stop.endLine,
        stop.endCol
    )
  }
}

// A syntax error that occured when parsing a document. It is generated
// by the ANTLR machinery and we transform it into this format.
final case class SyntaxError(symbol: String, loc: SourceLocation, reason: String)

// Syntax error exception
final class SyntaxException(message: String) extends Exception(message) {
  def this(msg: String, loc: SourceLocation) = {
    this(SyntaxException.formatMessage(msg, loc))
  }
  def this(msg: String, cause: Throwable) = {
    this(msg)
    initCause(cause)
  }
  def this(errors: Seq[SyntaxError]) = {
    this(SyntaxException.formatMessageFromErrorList(errors))
  }
}

object SyntaxException {
  def formatMessage(msg: String, loc: SourceLocation): String = {
    s"${msg} at ${loc}"
  }

  def formatMessageFromErrorList(errors: Seq[SyntaxError]): String = {
    // make one big report on all the syntax errors
    val messages = errors.map {
      case SyntaxError(symbol, locSource, msg) =>
        s"${msg} at ${symbol} at ${locSource}"
    }
    messages.mkString("\n")
  }
}

/**
  * A WDL comment.
  * @param value the comment string, including prefix ('#')
  * @param loc the location of the comment in the source file
  */
case class Comment(value: String, loc: SourceLocation) extends Ordered[Comment] {
  override def compare(that: Comment): Int = loc.line - that.loc.line
}

case class CommentMap(comments: TreeMap[Int, Comment]) {
  def nonEmpty: Boolean = {
    comments.nonEmpty
  }

  lazy val minLine: Int = comments.keys.min

  lazy val maxLine: Int = comments.keys.max

  def filterWithin(range: Seq[Int]): CommentMap = {
    CommentMap(comments.view.filterKeys(range.contains).to(TreeMap))
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
  val empty: CommentMap = CommentMap(TreeMap.empty)
}
