package wdlTools.syntax

import java.net.URL

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import wdlTools.util.{Options, SourceCode, Util}

import scala.collection.mutable

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

// Syntax error exception
final class SyntaxException(message: String) extends Exception(message) {
  def this(msg: String, text: TextSource, docSourceURL: Option[URL] = None) = {
    this(formatMessage(msg, text, docSourceURL))
  }

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

trait DocumentWalker[T] {
  def walk(visitor: (URL, Document, mutable.Map[URL, T]) => Unit): Map[URL, T]
}

abstract class WdlParser(opts: Options, loader: SourceCode.Loader) {
  def canParse(sourceCode: SourceCode): Boolean

  def apply(sourceCode: SourceCode): Document

  def parse(url: URL): Document = {
    apply(loader.apply(url))
  }

  def getDocSourceURL(addr: String): URL = {
    Util.getURL(addr, opts.localDirectories)
  }

  case class Walker[T](rootURL: URL,
                       sourceCode: Option[SourceCode] = None,
                       results: mutable.Map[URL, T] = mutable.HashMap.empty[URL, T])
      extends DocumentWalker[T] {
    def extractDependencies(document: Document): Map[URL, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, addr, doc, _, _) if doc.isDefined =>
          Some(Util.getURL(addr.value, opts.localDirectories) -> doc.get)
        case _ => None
      }.toMap
    }

    def walk(visitor: (URL, Document, mutable.Map[URL, T]) => Unit): Map[URL, T] = {
      def addDocument(url: URL, doc: Document): Unit = {
        if (!results.contains(url)) {
          visitor(url, doc, results)
          if (opts.followImports) {
            extractDependencies(doc).foreach {
              case (uri, doc) => addDocument(uri, doc)
            }
          }
        }
      }

      val document = apply(sourceCode.getOrElse(loader.apply(rootURL)))
      addDocument(rootURL, document)
      results.toMap
    }
  }
}

trait WdlFragmentParser {
  def parseExpr(text: String): Expr
  def parseType(text: String): Type
}
