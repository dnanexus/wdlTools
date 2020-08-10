package wdlTools.linter

import spray.json.JsValue
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.util.{FileSourceResolver, FileUtils, Logger}

object Severity extends Enumeration {
  type Severity = Value
  val Error, Warning, Ignore = Value
  val Default: Severity = Error
}

case class RuleConf(id: String,
                    name: String,
                    description: String,
                    severity: Severity,
                    options: Map[String, JsValue])

case class LintEvent(rule: RuleConf, loc: SourceLocation, message: Option[String] = None)
    extends Ordered[LintEvent] {
  override def compare(that: LintEvent): Int = {
    val cmp = loc.compare(that.loc)
    if (cmp != 0) {
      cmp
    } else {
      rule.id.compareTo(that.rule.id)
    }
  }
}
