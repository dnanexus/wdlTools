package wdlTools.syntax.v2

import java.net.URL

import org.openwdl.wdl.parser.v2.{WdlV2Lexer, WdlV2Parser}
import wdlTools.syntax.Antlr4Util.{
  Grammar,
  ParseTreeListenerFactory,
  WdlAggregatingErrorListener,
  createGrammar
}
import wdlTools.util.{Options, SourceCode}

case class WdlV2Grammar(override val lexer: WdlV2Lexer,
                        override val parser: WdlV2Parser,
                        override val errListener: WdlAggregatingErrorListener,
                        override val docSourceUrl: Option[URL] = None,
                        override val opts: Options)
    extends Grammar(lexer, parser, errListener, docSourceUrl, opts)

object WdlV2Grammar {
  def newInstance(sourceCode: SourceCode, opts: Options): WdlV2Grammar = {
    createGrammar[WdlV2Lexer, WdlV2Parser, WdlV2Grammar](sourceCode, opts)
  }

  def newInstance(text: String, opts: Options): WdlV2Grammar = {
    createGrammar[WdlV2Lexer, WdlV2Parser, WdlV2Grammar](text, None, opts)
  }
}
