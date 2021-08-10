package wdlTools.linter

import spray.json.JsValue
import wdlTools.syntax.SourceLocation

object Severity extends Enumeration {
  type Severity = Value
  val Error, Warning, Ignore = Value
  val Default: Severity = Error
}

case class RuleConf(id: String,
                    name: String,
                    description: String,
                    severity: Severity.Severity,
                    options: Map[String, JsValue])

case class LintEvent(rule: RuleConf, loc: SourceLocation, message: Option[String] = None)
    extends Ordered[LintEvent] {
  override def compare(that: LintEvent): Int = {
    loc.compare(that.loc) match {
      case 0   => rule.id.compareTo(that.rule.id)
      case cmp => cmp
    }
  }
}
