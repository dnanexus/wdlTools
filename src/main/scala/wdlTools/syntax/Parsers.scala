package wdlTools.syntax

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.util.{Options, SourceCode, Util}

import scala.collection.mutable

case class Parsers(opts: Options = Options(), defaultLoader: Option[SourceCode.Loader] = None) {
  private val loader: SourceCode.Loader = defaultLoader.getOrElse(SourceCode.Loader(opts))
  private lazy val parsers: Map[WdlVersion, WdlParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(opts, loader),
      WdlVersion.V1 -> v1_0.ParseAll(opts, loader)
  )
  private lazy val typeParsers: Map[WdlVersion, WdlTypeParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseType(opts),
      WdlVersion.V1 -> v1_0.ParseType(opts)
  )
  private lazy val exprParsers: Map[WdlVersion, WdlExprParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseExpr(opts),
      WdlVersion.V1 -> v1_0.ParseExpr(opts)
  )

  def getParser(url: URL): WdlParser = {
    getParser(loader.apply(url))
  }

  def getParser(sourceCode: SourceCode): WdlParser = {
    WdlVersion.All.foreach { ver =>
      val parser = parsers(ver)
      if (parser.canParse(sourceCode)) {
        return parser
      }
    }
    throw new Exception(s"No parser is able to parse document ${sourceCode.url}")
  }

  def getParser(wdlVersion: WdlVersion): WdlParser = {
    parsers(wdlVersion)
  }

  def parse(path: Path): Document = {
    parse(Util.getURL(path))
  }

  def parse(url: URL): Document = {
    val sourceCode = loader.apply(url)
    val parser = getParser(sourceCode)
    parser.apply(sourceCode)
  }

  def getDocumentWalker[T](
      url: URL,
      results: mutable.Map[URL, T] = mutable.HashMap.empty[URL, T]
  ): DocumentWalker[T] = {
    val sourceCode = loader.apply(url)
    val parser = getParser(sourceCode)
    parser.Walker(url, Some(sourceCode), results)
  }

  def getTypeParser(wdlVersion: WdlVersion): WdlTypeParser = {
    typeParsers(wdlVersion)
  }

  def getExprParser(wdlVersion: WdlVersion): WdlExprParser = {
    exprParsers(wdlVersion)
  }
}
