package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import wdlTools.util.{FileSource, FileSourceResolver}

trait DocumentWalker[T] {
  def walk(visitor: (Document, T) => T): T
}

abstract class WdlParser(followImports: Boolean = false,
                         fileResolver: FileSourceResolver = FileSourceResolver.get) {
  // cache of documents that have already been fetched and parsed.
  private var docCache: Map[String, Option[AbstractSyntax.Document]] = Map.empty

  protected def followImport(uri: String): Option[AbstractSyntax.Document] = {
    docCache.get(uri) match {
      case None =>
        val aDoc = Some(parseDocument(fileResolver.resolve(uri)))
        docCache += (uri -> aDoc)
        aDoc
      case Some(aDoc) => aDoc
    }
  }

  def canParse(fileSource: FileSource): Boolean

  def parseDocument(fileSource: FileSource): Document

  def parseExpr(text: String): Expr

  def parseType(text: String): Type

  case class Walker[T](fileSource: FileSource, start: T) extends DocumentWalker[T] {
    def extractDependencies(document: Document): Map[FileSource, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, addr, doc, _) if doc.isDefined =>
          Some(FileSourceResolver.get.resolve(addr.value) -> doc.get)
        case _ => None
      }.toMap
    }

    def walk(visitor: (Document, T) => T): T = {
      var visited: Set[String] = Set.empty
      var results: T = start

      def addDocument(fileSource: FileSource, doc: Document): Unit = {
        if (!visited.contains(fileSource.toString)) {
          visited += fileSource.toString
          results = visitor(doc, results)
          if (followImports) {
            extractDependencies(doc).foreach {
              case (fileSource, doc) => addDocument(fileSource, doc)
            }
          }
        }
      }

      val document = parseDocument(fileSource)
      addDocument(fileSource, document)
      results
    }
  }
}
