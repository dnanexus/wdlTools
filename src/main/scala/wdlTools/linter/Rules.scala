package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.{Lexer, Parser, ParserRuleContext, Token}
import wdlTools.syntax.Antlr4Util
import wdlTools.syntax.Antlr4Util.{Antlr4ParserListener, Grammar}

import scala.collection.mutable

object Rules {
  abstract class LinterParserRule(val id: String, errors: mutable.Buffer[LinterError])
      extends Antlr4ParserListener {
    protected def addError(tok: Token, docSourceUrl: Option[URL] = None): Unit = {
      errors.append(LinterError(id, Antlr4Util.getSourceText(tok, docSourceUrl)))
    }
  }

  case class WhitespaceTabsRule(errors: mutable.Buffer[LinterError])
      extends LinterParserRule("P001", errors) {
    override def notify(grammar: Grammar[Lexer, Parser], ctx: ParserRuleContext): Unit = {
      val hidden = grammar.getHiddenTokens(ctx)
      hidden.foreach {
        case tok: Token if tok.getText.contains("\t") => addError(tok, grammar.docSourceUrl)
      }
    }
  }
}
