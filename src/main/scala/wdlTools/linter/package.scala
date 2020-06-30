package wdlTools.linter

import wdlTools.linter.Severity.Severity
import wdlTools.syntax.TextSource
import wdlTools.util.FileSource

object Severity extends Enumeration {
  type Severity = Value
  val Error, Warning, Ignore = Value
  val Default: Severity = Error
}

case class LintEvent(ruleId: String,
                     severity: Severity,
                     textSource: TextSource,
                     docSource: FileSource,
                     message: Option[String] = None)
    extends Ordered[LintEvent] {
  override def compare(that: LintEvent): Int = {
    val cmp = textSource.compare(that.textSource)
    if (cmp != 0) {
      cmp
    } else {
      ruleId.compareTo(that.ruleId)
    }
  }
}
