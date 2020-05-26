package wdlTools.syntax

import java.net.URL

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import wdlTools.util.{Options, SourceCode, Util}

trait DocumentWalker[T] {
  def walk(visitor: (Document, T) => T): T
}

abstract class WdlParser(opts: Options) {
  // cache of documents that have already been fetched and parsed.
  private var docCache: Map[URL, Option[AbstractSyntax.Document]] = Map.empty

  protected def followImport(url: URL): Option[AbstractSyntax.Document] = {
    docCache.get(url) match {
      case None =>
        val aDoc = Some(parseDocument(SourceCode.loadFrom(url)))
        docCache += (url -> aDoc)
        aDoc
      case Some(aDoc) => aDoc
    }
  }

  def canParse(sourceCode: SourceCode): Boolean

  def parseDocument(url: URL): AbstractSyntax.Document = {
    parseDocument(SourceCode.loadFrom(url))
  }

  def parseDocument(sourceCode: SourceCode): Document

  def parseExpr(text: String): Expr

  def parseType(text: String): Type

  protected def getDocSourceUrl(addr: String): URL = {
    Util.getUrl(addr, opts.localDirectories)
  }

  def getDocumentWalker[T](url: URL, start: T): Walker[T] = {
    Walker[T](SourceCode.loadFrom(url), start)
  }

  case class Walker[T](sourceCode: SourceCode, start: T) extends DocumentWalker[T] {
    def extractDependencies(document: Document): Map[URL, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, addr, doc, _) if doc.isDefined =>
          Some(Util.getUrl(addr.value, opts.localDirectories) -> doc.get)
        case _ => None
      }.toMap
    }

    def walk(visitor: (Document, T) => T): T = {
      var visited: Set[Option[URL]] = Set.empty
      var results: T = start

      def addDocument(url: Option[URL], doc: Document): Unit = {
        if (!visited.contains(url)) {
          visited += url
          results = visitor(doc, results)
          if (opts.followImports) {
            extractDependencies(doc).foreach {
              case (uri, doc) => addDocument(Some(uri), doc)
            }
          }
        }
      }

      val document = parseDocument(sourceCode)
      addDocument(sourceCode.url, document)
      results
    }
  }
}
