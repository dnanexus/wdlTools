package wdlTools.linter

import org.antlr.v4.runtime.ParserRuleContext
import wdlTools.syntax.v1_0.WdlV1ParserListener
import wdlTools.syntax.Antlr4Util.{Antlr4ParserListener, Antlr4ParserListenerContext}
import wdlTools.syntax.v1_0.ConcreteSyntax.Element

object Rules {
  abstract class LinterRule[C <: ParserRuleContext] extends Antlr4ParserListener[C] {}

  case class WhitespaceTabsRule() extends LinterRule[ParserRuleContext] {
    override def notify(ctx: LinterRule[ParserRuleContext]): Unit = {
      val hidden = ctx.getHiddenTokens()
      println(s"[${hidden}]")
    }
  }
}
