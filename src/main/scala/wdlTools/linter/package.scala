package wdlTools.linter

import java.net.URL

import spray.json.JsValue
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.TextSource

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

case class LintEvent(rule: RuleConf,
                     textSource: TextSource,
                     docSourceUrl: Option[URL] = None,
                     message: Option[String] = None)
    extends Ordered[LintEvent] {
  override def compare(that: LintEvent): Int = {
    val cmp = textSource.compare(that.textSource)
    if (cmp != 0) {
      cmp
    } else {
      rule.id.compareTo(that.rule.id)
    }
  }
}
