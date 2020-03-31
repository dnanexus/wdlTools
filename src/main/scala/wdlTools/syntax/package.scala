package wdlTools

import java.net.URI

import wdlTools.syntax.AbstractSyntax.{Document, ImportDoc}
import wdlTools.util.{FetchURL, Options}

import scala.collection.mutable

package object syntax {
  def walkDocuments[T](rootURI: URI,
                       opts: Options,
                       followImports: Boolean = true,
                       results: mutable.Map[URI, T] = mutable.HashMap.empty)(
      visit: (URI, Document, mutable.Map[URI, T]) => Unit
  ): Map[URI, T] = {
    def extractDependencies(document: AbstractSyntax.Document): Map[URI, Document] = {
      document.elements.flatMap {
        case ImportDoc(_, _, url, doc) => Some(new URI(url.addr) -> doc)
        case _                         => None
      }.toMap
    }

    def addDocument(uri: URI, doc: Document): Unit = {
      if (!results.contains(uri)) {
        visit(uri, doc, results)
        if (followImports) {
          extractDependencies(doc).foreach {
            case (uri, doc) => addDocument(uri, doc)
          }
        }
      }
    }

    val sourceCode = FetchURL.readFromUri(rootURI, opts)
    val parser = ParseAll(opts)
    val document = parser.apply(sourceCode)
    addDocument(rootURI, document)

    results.toMap
  }
}
