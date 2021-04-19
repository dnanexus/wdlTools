package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.{Document, Expr, ImportDoc, Type}
import dx.util.{
  AddressableFileNode,
  AddressableFileSource,
  FileNode,
  FileSourceResolver,
  LocalFileSource,
  Logger,
  TraceLevel
}

import java.net.URI
import java.nio.file.Paths

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
    docCache.get(uri) match {
      case None =>
        logger.trace(s"parsing import ${uri}", minLevel = TraceLevel.VVerbose)
        val fn: FileNode = if (URI.create(uri).getScheme == null && parent.isDefined) {
          parent.get.resolve(uri) match {
            case fn: AddressableFileNode if fn.exists =>
              // a path relative to parent
              fn
            case _: LocalFileSource =>
              // the imported file is not relative to the parent, but
              // but LocalFileAccessProtocol may be configured to look
              // for it in a different folder
              fileResolver.fromPath(Paths.get(uri))
            case other =>
              throw new Exception(s"Not a FileNode: ${other}")
          }
        } else {
          // a full URI
          fileResolver.resolve(uri)
        }
        val aDoc = Some(parseDocument(fn))
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
