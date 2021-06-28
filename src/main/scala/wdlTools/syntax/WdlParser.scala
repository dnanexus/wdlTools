package wdlTools.syntax

import dx.util.{
  AddressableFileNode,
  AddressableFileSource,
  FileNode,
  FileSourceResolver,
  Logger,
  TraceLevel
}
import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}

trait DocumentWalker[T] {
  def walk(visitor: (Document, T) => T): T
}

abstract class WdlParser(followImports: Boolean = false,
                         fileResolver: FileSourceResolver = FileSourceResolver.get,
                         logger: Logger) {
  // cache of documents that have already been fetched and parsed.
  private var docCache: Map[String, Option[AbstractSyntax.Document]] = Map.empty

  protected def followImport(
      uri: String,
      parent: Option[AddressableFileSource] = None
  ): Option[AbstractSyntax.Document] = {
    fileResolver.resolve(uri, parent) match {
      case fn: AddressableFileNode =>
        docCache.getOrElse(
            fn.address, {
              logger.trace(s"parsing import ${fn.address}", minLevel = TraceLevel.VVerbose)
              val doc = Some(parseDocument(fn))
              docCache += (fn.address -> doc)
              doc
            }
        )
      case fn => Some(parseDocument(fn))
    }
  }

  def canParse(fileSource: FileNode): Boolean

  def parseDocument(fileSource: FileNode): Document

  def parseExpr(text: String): Expr

  def parseType(text: String): Type

  case class Walker[T](fileSource: FileNode, start: T) extends DocumentWalker[T] {
    def extractDependencies(document: Document,
                            parent: Option[AddressableFileSource]): Map[FileNode, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, addr, doc) if doc.isDefined =>
          Some(fileResolver.resolve(addr.value, parent) -> doc.get)
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
          val parent = fileSource match {
            case parent: AddressableFileSource => Some(parent)
            case _                             => None
          }
          if (followImports) {
            extractDependencies(doc, parent).foreach {
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
