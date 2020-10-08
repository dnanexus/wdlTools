package wdlTools.linter

import wdlTools.syntax.SourceLocation

object Severity extends Enumeration {
  type Severity = Value
  val Error, Warning, Ignore = Value
  val Default: Severity = Error
}

case class LintEvent(ruleId: String,
                     severity: Severity.Severity,
                     loc: SourceLocation,
                     message: Option[String] = None)
    extends Ordered[LintEvent] {
  override def compare(that: LintEvent): Int = {
    val cmp = loc.compare(that.loc)
    if (cmp != 0) {
      cmp
    } else {
      ruleId.compareTo(that.ruleId)
    }
  }
}
