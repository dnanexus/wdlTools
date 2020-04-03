package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.{Document, ImportDoc}
import wdlTools.util.{Options, SourceCode, URL}

import scala.collection.mutable

sealed abstract class WdlVersion(val name: String, val order: Int) extends Ordered[WdlVersion] {
  def compare(that: WdlVersion): Int = this.order - that.order
}

object WdlVersion {
  case object V1_0 extends WdlVersion("1.0", 0)
  case object Draft_2 extends WdlVersion("draft-2", 1)

  val All: Vector[WdlVersion] = Vector(V1_0, Draft_2).sortWith(_ < _)

  def fromName(name: String): WdlVersion = {
    All.collectFirst { case v if v.name == name => v }.get
  }
}

trait DocumentWalker[T] {
  def walk(visitor: (URL, Document, mutable.Map[URL, T]) => Unit): Map[URL, T]
}

abstract class Parser(opts: Options, loader: SourceCode.Loader) {
  def canParse(sourceCode: SourceCode): Boolean

  def apply(sourceCode: SourceCode): AbstractSyntax.Document

  def parse(url: URL): Document = {
    apply(loader.apply(url))
  }

  case class Walker[T](rootURL: URL,
                       sourceCode: Option[SourceCode] = None,
                       results: mutable.Map[URL, T] = mutable.HashMap.empty[URL, T])
      extends DocumentWalker[T] {
    def extractDependencies(document: AbstractSyntax.Document): Map[URL, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, url, doc, _) => Some(url -> doc)
        case _                            => None
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
