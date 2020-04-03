package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.util.{Options, SourceCode, URL}

import scala.collection.mutable

case class Parsers(opts: Options, defaultLoader: Option[SourceCode.Loader] = None) {
  private val loader: SourceCode.Loader = defaultLoader.getOrElse(SourceCode.Loader(opts))
  private val parsers: Map[WdlVersion, Parser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(opts, loader),
      WdlVersion.V1_0 -> v1_0.ParseAll(opts, loader)
  )

  def getParser(url: URL): Parser = {
    getParser(loader.apply(url))
  }

  def getParser(sourceCode: SourceCode): Parser = {
    WdlVersion.All.foreach { ver =>
      val parser = parsers(ver)
      if (parser.canParse(sourceCode)) {
        return parser
      }
    }
    throw new Exception(s"No parser is able to parse document ${sourceCode.url}")
  }

  def getParser(wdlVersion: WdlVersion): Parser = {
    parsers(wdlVersion)
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
}
