package wdlTools

import ConcreteSyntax._

// parse and follow imports
object ParseAll {

  var docCache = Map.empty[URL, Document]

  private def followImport(url: URL): Document = {
    docCache.get(url) match {
      case None =>
        val docText = FetchURL.apply(url)
        val doc = ParseDocument.apply(docText)
        docCache = docCache + (url -> doc)
        doc
      case Some(doc) =>
        doc
    }
  }

  def apply(url: URL, tracing: Boolean = false): Document = {
    val sourceCode = FetchURL.apply(url)
    val doc = ParseDocument.apply(sourceCode, tracing)

    // scan for import statements and follow them
    val elems = doc.elements.map {
      case ImportDoc(name, aliases, url) =>
        val importDoc = followImport(url)
        ImportDocElaborated(name, aliases, importDoc)

      // all other document parts are unchanged.
      case x => x
    }
    Document(doc.version, elems)
  }
}
