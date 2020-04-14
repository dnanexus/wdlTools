package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.ParserRuleContext
import wdlTools.syntax.{Parsers, WdlDocumentParser, WdlVersion}
import wdlTools.syntax.v1_0.ConcreteSyntax._
import wdlTools.util.Options

case class Linter(opts: Options) {
  def apply(url: URL): Unit = {
    val parsers = Parsers(opts)
    val parser: WdlDocumentParser = parsers.getParser(WdlVersion.V1)
    parser.addListener[Element, ParserRuleContext](Rules.WhitespaceTabsRule())
    parser.apply(url)
  }
}
