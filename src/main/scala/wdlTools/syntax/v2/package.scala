package wdlTools.syntax.v2

import org.antlr.v4.runtime.{CharStream, CommonTokenStream}
import org.openwdl.wdl.parser.v2.{WdlV2Lexer, WdlV2Parser}
import wdlTools.syntax.Antlr4Util.GrammarFactory
import wdlTools.util.Options

case class WdlV2GrammarFactory(opts: Options)
    extends GrammarFactory[WdlV2Lexer, WdlV2Parser](opts) {
  override def createLexer(charStream: CharStream): WdlV2Lexer = {
    new WdlV2Lexer(charStream)
  }

  override def createParser(tokenStream: CommonTokenStream): WdlV2Parser = {
    new WdlV2Parser(tokenStream)
  }
}
