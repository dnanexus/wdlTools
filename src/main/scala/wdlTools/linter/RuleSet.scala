package wdlTools.linter

import wdlTools.linter.Rules.{IndentRule, WhitespaceTabsRule}

import scala.collection.mutable

case class RuleSet(errors: mutable.Buffer[LinterError] = mutable.ArrayBuffer.empty) {
  lazy val parserRuleFactories = Vector(
      LinterParserRuleFactory[WhitespaceTabsRule](id = "P001", errors),
      LinterParserRuleFactory[IndentRule](id = "P002", errors)
  )

  lazy val astVisitors: Vector[LinterASTRule] = Vector()
}
