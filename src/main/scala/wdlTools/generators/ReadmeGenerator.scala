package wdlTools.generators

import java.net.URI

import wdlTools.syntax.AbstractSyntax.Document

import scala.collection.mutable

class ReadmeGenerator(renderer: Renderer = SspRenderer(),
                      readmes: mutable.HashMap[URI, String] = mutable.HashMap.empty) {
  def generateReadmes(uri: URI, document: Document, followImports: Boolean = true): Unit = {}
}
