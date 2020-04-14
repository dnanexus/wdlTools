package wdlTools.linter

import org.antlr.v4.runtime.ParserRuleContext
import wdlTools.syntax.v1_0.WdlV1ParserListener
import wdlTools.syntax.Antlr4Util.Antlr4ParserListenerContext
import wdlTools.syntax.v1_0.ConcreteSyntax.Element

object Rules {
  abstract class LinterRule extends WdlV1ParserListener[Element, ParserRuleContext]

  case class WhitespaceTabsRule() extends LinterRule {
    override def notify(element: Element,
                        ctx: Antlr4ParserListenerContext[ParserRuleContext]): Unit = {
      val hidden = ctx.getHiddenTokens()
      println(s"[${hidden}]")
    }
  }
}
