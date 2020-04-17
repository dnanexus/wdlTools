package wdlTools.linter

import org.antlr.v4.runtime.{Lexer, Parser, ParserRuleContext, Token}
import wdlTools.syntax.Antlr4Util.Grammar

import scala.collection.mutable

object Rules {
  case class WhitespaceTabsRule(id: String,
                                errors: mutable.Buffer[LinterError],
                                grammar: Grammar[Lexer, Parser])
      extends LinterParserRule(id, errors) {
    override def enterEveryRule(ctx: ParserRuleContext): Unit = {
      val hidden = grammar.getHiddenTokens(ctx)
      hidden.foreach {
        case tok: Token if tok.getText.contains("\t") => addError(tok, grammar.docSourceUrl)
        case other                                    => println(s"Found other ${other}")
      }
    }
  }

  case class IndentRule(id: String,
                        errors: mutable.Buffer[LinterError],
                        grammar: Grammar[Lexer, Parser])
      extends LinterParserRule(id, errors) {
    override def enterEveryRule(ctx: ParserRuleContext): Unit = {
      val hidden = grammar.getHiddenTokens(ctx)
      print(hidden.toString)
    }
  }
}
