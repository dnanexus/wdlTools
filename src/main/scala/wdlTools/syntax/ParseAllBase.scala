package wdlTools.syntax

import java.net.URL

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import wdlTools.util.{Options, SourceCode}

import scala.collection.mutable

trait DocumentWalker[T] {
  def walk(visitor: (URL, Document, mutable.Map[URL, T]) => Unit): Map[URL, T]
}

abstract class ParseAllBase(opts: Options, loader: SourceCode.Loader) {
  // cache of documents that have already been fetched and parsed.
  private val docCache: mutable.Map[URL, AbstractSyntax.Document] = mutable.Map.empty

  protected def followImport(url: URL): AbstractSyntax.Document = {
    docCache.get(url) match {
      case None =>
        val aDoc = apply(loader.apply(url))
        docCache(url) = aDoc
        aDoc
      case Some(aDoc) => aDoc
    }
  }

  def canParse(sourceCode: SourceCode): Boolean

  def apply(url: URL): AbstractSyntax.Document = {
    apply(loader.apply(url))
  }

  def apply(sourceCode: SourceCode): Document

  def getWalker[T](url: URL,
                   results: mutable.Map[URL, T] = mutable.HashMap.empty[URL, T]): Walker[T] = {
    Walker[T](loader.apply(url), results)
  }

  case class Walker[T](sourceCode: SourceCode,
                       results: mutable.Map[URL, T] = mutable.HashMap.empty[URL, T])
      extends DocumentWalker[T] {
    def extractDependencies(document: Document): Map[URL, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, url, doc, _, _) => Some(url -> doc)
        case _                               => None
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

      val document = apply(sourceCode)
      addDocument(sourceCode.url, document)
      results.toMap
    }
  }
}

trait WdlFragmentParser {
  def parseExpr(text: String): Expr
  def parseType(text: String): Type
}
