package wdlTools.syntax

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.util.{Options, SourceCode, Util}

import scala.collection.mutable

case class Parsers(opts: Options = Options(), defaultLoader: Option[SourceCode.Loader] = None) {
  private val loader: SourceCode.Loader = defaultLoader.getOrElse(SourceCode.Loader(opts))
  private lazy val parsers: Map[WdlVersion, ParseAllBase] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(opts, loader),
      WdlVersion.V1 -> v1_0.ParseAll(opts, loader)
  )
  private lazy val fragmentParsers: Map[WdlVersion, WdlFragmentParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseFragment(opts),
      WdlVersion.V1 -> v1_0.ParseFragment(opts)
  )

  def getParser(url: URL): ParseAllBase = {
    getParser(loader.apply(url))
  }

  def getParser(sourceCode: SourceCode): ParseAllBase = {
    WdlVersion.All.foreach { ver =>
      val parser = parsers(ver)
      if (parser.canParse(sourceCode)) {
        return parser
      }
    }
    throw new Exception(s"No parser is able to parse document ${sourceCode.url}")
  }

  def getParser(wdlVersion: WdlVersion): ParseAllBase = {
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
    parser.Walker(sourceCode, results)
  }

  def getFragmentParser(wdlVersion: WdlVersion): WdlFragmentParser = {
    fragmentParsers(wdlVersion)
  }
}
