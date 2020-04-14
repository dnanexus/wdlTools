package wdlTools.syntax

import java.net.URL

import org.antlr.v4.runtime.ParserRuleContext
import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import wdlTools.syntax.Antlr4Util.Antlr4ParserListener
import wdlTools.util.{Options, SourceCode}

import scala.collection.mutable

trait DocumentWalker[T] {
  def walk(visitor: (URL, Document, mutable.Map[URL, T]) => Unit): Map[URL, T]
}

abstract class WdlDocumentParser(opts: Options, loader: SourceCode.Loader) {
  def addParserListener[C <: ParserRuleContext](key: Int, listener: Antlr4ParserListener[C]): Unit

  def canParse(sourceCode: SourceCode): Boolean

  def apply(url: URL): Document

  def apply(sourceCode: SourceCode): Document

  def parse(url: URL): Document = {
    apply(loader.apply(url))
  }

  case class Walker[T](rootURL: URL,
                       sourceCode: Option[SourceCode] = None,
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
