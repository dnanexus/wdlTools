package wdlTools.linter

import wdlTools.syntax.TextSource

case class LinterError(ruleId: String, textSource: TextSource)
