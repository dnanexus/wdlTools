package wdlTools.linter

import java.net.URL

import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.Options

case class Linter(opts: Options) {
  def apply(url: URL): Unit = {
    val ruleSet = RuleSet()

    val parsers = Parsers(opts, listenerFactories = ruleSet.parserRuleFactories)
    val parser: ParseAll = parsers.getParser(WdlVersion.V1).asInstanceOf[ParseAll]
    val doc = parser.apply(url)

    val astWalker = LinterASTWalker(opts, ruleSet.astVisitors)
    astWalker.apply(doc)

    ruleSet.errors.foreach { err =>
      println(s"${err.textSource} ${err.ruleId}")
    }
  }
}
