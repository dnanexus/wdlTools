package wdlTools.syntax.draft_2

import java.net.URL

import org.openwdl.wdl.parser.draft_2.{WdlDraft2Lexer, WdlDraft2Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, WdlAggregatingErrorListener, createGrammar}
import wdlTools.util.{Options, SourceCode}

case class WdlDraft2Grammar(override val lexer: WdlDraft2Lexer,
                            override val parser: WdlDraft2Parser,
                            override val errListener: WdlAggregatingErrorListener,
                            override val docSourceUrl: Option[URL] = None,
                            override val opts: Options)
    extends Grammar(lexer, parser, errListener, docSourceUrl, opts)

object WdlDraft2Grammar {
  def newInstance(sourceCode: SourceCode, opts: Options): WdlDraft2Grammar = {
    createGrammar[WdlDraft2Lexer, WdlDraft2Parser, WdlDraft2Grammar](
        sourceCode,
        opts
    )
  }

  def newInstance(text: String, opts: Options): WdlDraft2Grammar = {
    createGrammar[WdlDraft2Lexer, WdlDraft2Parser, WdlDraft2Grammar](
        text,
        None,
        opts
    )
  }
}
