package wdlTools.syntax

import java.nio.file.Path

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import dx.util.{FileNode, FileSourceResolver, Logger, TraceLevel}

trait DocumentWalker[T] {
  def walk(visitor: (Document, T) => T): T
}

abstract class WdlParser(followImports: Boolean = false,
                         fileResolver: FileSourceResolver = FileSourceResolver.get,
                         logger: Logger) {
  // cache of documents that have already been fetched and parsed.
  private var docCache: Map[String, Option[AbstractSyntax.Document]] = Map.empty

  protected def followImport(uri: String,
                             parent: Option[Path] = None): Option[AbstractSyntax.Document] = {
    docCache.get(uri) match {
      case None =>
        logger.trace(s"parsing import ${uri}", minLevel = TraceLevel.VVerbose)
        val fileResolverWithParent = parent match {
          // Prepend the parent of the current directory to the search path
          // in case we need to resolve relative imports.
          case Some(path) => fileResolver.addToLocalSearchPath(Vector(path), append = false)
          case None       => fileResolver
        }
        val aDoc = Some(parseDocument(fileResolverWithParent.resolve(uri)))
        docCache += (uri -> aDoc)
        aDoc
      case Some(aDoc) => aDoc
    }
  }

  def canParse(fileSource: FileNode): Boolean

  def parseDocument(fileSource: FileNode): Document

  def parseExpr(text: String): Expr

  def parseType(text: String): Type

  case class Walker[T](fileSource: FileNode, start: T) extends DocumentWalker[T] {
    def extractDependencies(document: Document): Map[FileNode, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, addr, doc, _) if doc.isDefined =>
          Some(FileSourceResolver.get.resolve(addr.value) -> doc.get)
        case _ => None
      }.toMap
    }

    def walk(visitor: (Document, T) => T): T = {
      var visited: Set[String] = Set.empty
      var results: T = start

      def addDocument(fileSource: FileNode, doc: Document): Unit = {
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
