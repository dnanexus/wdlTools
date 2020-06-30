package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.util.{FileSource, Options}

case class Parsers(
    opts: Options,
    listenerFactories: Vector[ParseTreeListenerFactory] = Vector.empty,
    errorHandler: Option[Vector[SyntaxError] => Boolean] = None
) {
  private val parsers: Map[WdlVersion, WdlParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(opts, listenerFactories, errorHandler),
      WdlVersion.V1 -> v1.ParseAll(opts, listenerFactories, errorHandler),
      WdlVersion.V2 -> v2.ParseAll(opts, listenerFactories, errorHandler)
  )

  def getParser(fileSource: FileSource): WdlParser = {
    parsers.values.collectFirst {
      case parser if parser.canParse(fileSource) => parser
    } match {
      case Some(parser) => parser
      case _            => throw new Exception(s"No parser is able to parse document ${fileSource}")
    }
  }

  def getParser(wdlVersion: WdlVersion): WdlParser = {
    parsers.get(wdlVersion) match {
      case Some(parser) => parser
      case _            => throw new Exception(s"No parser defined for WdlVersion ${wdlVersion}")
    }
  }

  def parseDocument(fileSource: FileSource): Document = {
    val parser = getParser(fileSource)
    parser.parseDocument(fileSource)
  }

  def getDocumentWalker[T](fileSource: FileSource, results: T): DocumentWalker[T] = {
    val parser = getParser(fileSource)
    parser.Walker(fileSource, results)
  }
}
