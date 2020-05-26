package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.{ParserRuleContext, Token}
import wdlTools.syntax
import wdlTools.syntax.Antlr4Util.Grammar
import wdlTools.syntax.{AllParseTreeListener, Antlr4Util, TextSource, WdlVersion}

import scala.collection.mutable

// These are mostly to check things related to whitespace, which is not accessible from the AST
object ParserRules {
  type LinterParserRuleApplySig = (RuleConf, Grammar) => LinterParserRule

  // ideally we could provide the id as a class annotation, but dealing with annotations
  // in Scala is currently horrendous - for how it would be done, see
  // https://stackoverflow.com/questions/23046958/accessing-an-annotation-value-in-scala
  class LinterParserRule(conf: RuleConf, docSourceUrl: Option[URL]) extends AllParseTreeListener {
    private var events: Vector[LintEvent] = Vector.empty

    def getEvents: Vector[LintEvent] = events

    protected def addEventFromTokens(tok: Token,
                                     stopToken: Option[Token] = None,
                                     message: Option[String] = None): Unit = {
      addEvent(Antlr4Util.getTextSource(tok, stopToken), message)
    }

    protected def addEvent(textSource: TextSource, message: Option[String] = None): Unit = {
      events :+= LintEvent(conf, textSource, docSourceUrl, message)
    }
  }

  abstract class HiddenTokensLinterParserRule(conf: RuleConf, grammar: Grammar)
      extends LinterParserRule(conf, grammar.docSourceUrl) {
    private val tokenIndexes: mutable.Set[Int] = mutable.HashSet.empty

    protected def addEvent(tok: Token): Unit = {
      val idx = tok.getTokenIndex
      if (!tokenIndexes.contains(idx)) {
        // properly construct TextSource to deal with newlines
        val text = tok.getText
        val lines = text.linesWithSeparators.toVector
        val textSource = syntax.TextSource(
            line = tok.getLine,
            col = tok.getCharPositionInLine,
            endLine = tok.getLine + math.max(lines.size, 1) - 1,
            endCol = if (lines.size <= 1) {
              tok.getCharPositionInLine + text.length
            } else {
              lines.last.length + 1
            }
        )
        addEvent(textSource)
        tokenIndexes.add(idx)
      }
    }
  }

  abstract class EveryRuleHiddenTokensLinterParserRule(conf: RuleConf, grammar: Grammar)
      extends HiddenTokensLinterParserRule(conf, grammar) {
    override def exitEveryRule(ctx: ParserRuleContext): Unit = {
      grammar
        .getHiddenTokens(ctx, within = true)
        .filter(isViolation)
        .foreach(addEvent)
    }

    def isViolation(token: Token): Boolean
  }

  case class WhitespaceTabsRule(conf: RuleConf, grammar: Grammar)
      extends EveryRuleHiddenTokensLinterParserRule(conf, grammar) {
    override def isViolation(token: Token): Boolean = {
      token.getText.contains("\t")
    }
  }

  case class OddIndentRule(conf: RuleConf, grammar: Grammar)
      extends EveryRuleHiddenTokensLinterParserRule(conf, grammar) {
    private val indentRegex = "\n+([ \t]+)".r

    override def isViolation(token: Token): Boolean = {
      // find any tokens that contain a newline followed by an odd number of spaces
      indentRegex.findAllMatchIn(token.getText).exists { ws =>
        ws.group(1)
          .map {
            case ' '  => 1
            case '\t' => 2
          }
          .sum % 2 == 1
      }
    }
  }

  case class MultipleBlankLineRule(conf: RuleConf, grammar: Grammar)
      extends EveryRuleHiddenTokensLinterParserRule(conf, grammar) {
    private val multipleReturns = "(\n\\s*){3,}".r

    override def isViolation(token: Token): Boolean = {
      multipleReturns.findFirstIn(token.getText).isDefined
    }
  }

  case class TopLevelIndentRule(conf: RuleConf, grammar: Grammar)
      extends HiddenTokensLinterParserRule(conf, grammar) {
    private val endWhitespaceRegex = "\\s$".r

    def checkIndent(ctx: ParserRuleContext): Unit = {
      grammar
        .getHiddenTokens(ctx)
        .collectFirst {
          case tok
              if tok.getTokenIndex == ctx.getStart.getTokenIndex - 1 &&
                endWhitespaceRegex.findFirstIn(tok.getText).isDefined =>
            tok
        }
        .foreach(addEvent)
    }

    override def enterVersion(ctx: ParserRuleContext): Unit = {
      checkIndent(ctx)
    }

    override def enterImport_doc(ctx: ParserRuleContext): Unit = {
      checkIndent(ctx)
    }

    override def enterTask(ctx: ParserRuleContext): Unit = {
      checkIndent(ctx)
    }

    override def enterWorkflow(ctx: ParserRuleContext): Unit = {
      checkIndent(ctx)
    }
  }

  case class DeprecatedCommandStyleRule(conf: RuleConf, grammar: Grammar)
      extends HiddenTokensLinterParserRule(conf, grammar) {

    override def exitTask_command_expr_part(ctx: ParserRuleContext): Unit = {
      if (grammar.version >= WdlVersion.V1) {
        if (!ctx.start.getText.contains("~")) {
          addEventFromTokens(ctx.start, Some(ctx.stop))
        }
      }
    }
  }

  // TODO: load these dynamically from a file
  val allRules: Map[String, LinterParserRuleApplySig] = Map(
      "P001" -> WhitespaceTabsRule.apply,
      "P002" -> OddIndentRule.apply,
      "P003" -> MultipleBlankLineRule.apply,
      "P004" -> TopLevelIndentRule.apply,
      "P005" -> DeprecatedCommandStyleRule.apply
  )
}
