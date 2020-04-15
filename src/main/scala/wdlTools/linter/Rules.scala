package wdlTools.linter

import org.antlr.v4.runtime.{Lexer, Parser, ParserRuleContext, Token}
import wdlTools.syntax.Antlr4Util.Grammar

import scala.collection.mutable

object Rules {
  case class WhitespaceTabsRule(grammar: Grammar[Lexer, Parser],
                                errors: mutable.Buffer[LinterError])
      extends LinterParserRule("P001", errors) {
    override def enterEveryRule(ctx: ParserRuleContext): Unit = {
      val hidden = grammar.getHiddenTokens(ctx)
      hidden.foreach {
        case tok: Token if tok.getText.contains("\t") => addError(tok, grammar.docSourceUrl)
      }
    }
  }
}
