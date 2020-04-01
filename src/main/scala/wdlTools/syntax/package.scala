package wdlTools.syntax

import java.net.URI

import wdlTools.syntax.AbstractSyntax.{Document, ImportDoc}
import wdlTools.util.{FetchURL, Options}

import scala.collection.mutable

case class WalkDocuments[T](rootURI: URI,
                            opts: Options,
                            followImports: Boolean = true,
                            results: mutable.Map[URI, T] = mutable.HashMap.empty[URI, T]) {
  def extractDependencies(document: AbstractSyntax.Document): Map[URI, Document] = {
    document.elements.flatMap {
      case ImportDoc(_, _, url, doc, _) => Some(new URI(url.addr) -> doc)
      case _                            => None
    }.toMap
  }

  def addDocument(uri: URI,
                  doc: Document,
                  visit: (URI, Document, mutable.Map[URI, T]) => Unit): Unit = {
    if (!results.contains(uri)) {
      visit(uri, doc, results)
      if (followImports) {
        extractDependencies(doc).foreach {
          case (uri, doc) => addDocument(uri, doc, visit)
        }
      }
    }
  }

  def apply(visit: (URI, Document, mutable.Map[URI, T]) => Unit): Map[URI, T] = {
    val sourceCodeUrl = FetchURL.fromUri(rootURI, opts)
    val parser = ParseAll(opts)
    val document = parser.apply(sourceCodeUrl)
    addDocument(rootURI, document, visit)
    results.toMap
  }
}
