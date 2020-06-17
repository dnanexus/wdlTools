package wdlTools.syntax

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.util.{BasicOptions, Options, SourceCode, Util}

case class Parsers(opts: Options = BasicOptions(),
                   listenerFactories: Vector[ParseTreeListenerFactory] = Vector.empty,
                   errorHandler: Option[Vector[SyntaxError] => Boolean] = None) {

  private val parsers: Map[WdlVersion, WdlParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(opts, listenerFactories, errorHandler),
      WdlVersion.V1 -> v1.ParseAll(opts, listenerFactories, errorHandler),
      WdlVersion.V2 -> v2.ParseAll(opts, listenerFactories, errorHandler)
  )

  def getParser(url: URL): WdlParser = {
    getParser(SourceCode.loadFrom(url))
  }

  def getParser(sourceCode: SourceCode): WdlParser = {
    parsers.values.collectFirst {
      case parser if parser.canParse(sourceCode) => parser
    } match {
      case Some(parser) => parser
      case _            => throw new Exception(s"No parser is able to parse document ${sourceCode.url}")
    }
  }

  def getParser(wdlVersion: WdlVersion): WdlParser = {
    parsers.get(wdlVersion) match {
      case Some(parser) => parser
      case _            => throw new Exception(s"No parser defined for WdlVersion ${wdlVersion}")
    }
  }

  def parseDocument(path: Path): Document = {
    parseDocument(Util.pathToUrl(path))
  }

  def parseDocument(url: URL): Document = {
    parseDocument(SourceCode.loadFrom(url))
  }

  def parseDocument(sourceCode: SourceCode): Document = {
    val parser = getParser(sourceCode)
    parser.parseDocument(sourceCode)
  }

  def getDocumentWalker[T](url: URL, results: T): DocumentWalker[T] = {
    val sourceCode = SourceCode.loadFrom(url)
    val parser = getParser(sourceCode)
    parser.Walker(sourceCode, results)
  }
}
