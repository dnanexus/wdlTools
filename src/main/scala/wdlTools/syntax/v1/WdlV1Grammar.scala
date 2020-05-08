package wdlTools.syntax.v1

import java.net.URL

import org.openwdl.wdl.parser.v1.{WdlV1Lexer, WdlV1Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, WdlAggregatingErrorListener, createGrammar}
import wdlTools.util.{Options, SourceCode}

case class WdlV1Grammar(override val lexer: WdlV1Lexer,
                        override val parser: WdlV1Parser,
                        override val errListener: WdlAggregatingErrorListener,
                        override val docSourceUrl: Option[URL] = None,
                        override val opts: Options)
    extends Grammar(lexer, parser, errListener, docSourceUrl, opts)

object WdlV1Grammar {
  def newInstance(sourceCode: SourceCode, opts: Options): WdlV1Grammar = {
    createGrammar[WdlV1Lexer, WdlV1Parser, WdlV1Grammar](sourceCode, opts)
  }

  def newInstance(text: String, opts: Options): WdlV1Grammar = {
    createGrammar[WdlV1Lexer, WdlV1Parser, WdlV1Grammar](text, None, opts)
  }
}
