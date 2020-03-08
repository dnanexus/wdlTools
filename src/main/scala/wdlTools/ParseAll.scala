package wdlTools

import ConcreteSyntax.{Document, URL, ImportDoc, ImportDocElaborated}

import java.nio.file.Path
import scala.collection.mutable.Map

// parse and follow imports
case class ParseAll(trace: Boolean = false, localDirectories: Vector[Path] = Vector.empty) {
  private val docCache: Map[URL, Document] = Map.empty

  private def followImport(url: URL): Document = {
    docCache.get(url) match {
      case None =>
        val docText = FetchURL(localDirectories).apply(url)
        val doc = ParseDocument.apply(docText, trace)
        docCache(url) = doc
        doc
      case Some(doc) =>
        doc
    }
  }

  // start from a document [doc], and recursively dive into all the imported
  // documents. Replace all the raw import statements with fully elaborated ones.
  private def dfs(doc: Document): Document = {
    // scan for import statements and follow them
    val elems = doc.elements.map {
      case ImportDoc(name, aliases, url) =>
        val importedDocRaw = followImport(url)

        // recurse into the imported document
        val importedDocFull = dfs(importedDocRaw)

        // Replace the original statement with a new one
        ImportDocElaborated(name, aliases, importedDocFull)

      // all other document parts are unchanged.
      case x => x
    }.toVector
    Document(doc.version, elems)
  }

  // [dirs] : the directories where to search for imported documents
  //
  def apply(sourceCode: String): Document = {
    val topDoc: Document = ParseDocument.apply(sourceCode, trace)
    dfs(topDoc)
  }
}
