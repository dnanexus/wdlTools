package wdlTools.linter

import java.net.URL

import wdlTools.linter.Severity.Severity
import wdlTools.syntax.{AbstractSyntax, AbstractSyntaxTreeVisitor, WdlVersion}

import scala.collection.mutable

object AstRules {
  class LinterAstRule(id: String,
                      severity: Severity,
                      docSourceUrl: Option[URL],
                      events: mutable.Buffer[LintEvent])
      extends AbstractSyntaxTreeVisitor {
    protected def addEvent(element: AbstractSyntax.Element,
                           message: Option[String] = None): Unit = {
      events.append(LintEvent(id, severity, element.text, docSourceUrl, message))
    }
  }

  type LinterAstRuleApplySig = (
      String,
      Severity,
      WdlVersion,
      mutable.Buffer[LintEvent],
      Option[URL]
  ) => LinterAstRule

  // rules ported from miniwdl

  // rules ported from winstanley
  // rules not ported:
  // * missing command section - a command section is required, so the parser throws a SyntaxException if
  //   it doesn't find one
  // * value/callable lookup - these are caught by the type-checker
  // * no immediate declaration - the parser catches these
  // * wildcard outputs - the parser does not allow these even in draft-2
  // * unexpected/unsupplied inputs - the type-checker catches this

  case class NonPortableTaskRule(id: String,
                                 severity: Severity,
                                 version: WdlVersion,
                                 events: mutable.Buffer[LintEvent],
                                 docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    private val containerKeys = Set("docker", "container")

    override def visitTask(
        ctx: AbstractSyntaxTreeVisitor.VisitorContext[AbstractSyntax.Task]
    ): Unit = {
      if (ctx.element.runtime.isEmpty) {
        addEvent(ctx.element, Some("add a runtime section specifying a container"))
      } else if (!ctx.element.runtime.get.kvs.exists(kv => containerKeys.contains(kv.id))) {
        addEvent(ctx.element, Some("add a container to the runtime section"))
      }
    }
  }

  case class NoTaskInputsRule(id: String,
                              severity: Severity,
                              version: WdlVersion,
                              events: mutable.Buffer[LintEvent],
                              docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    override def visitTask(
        ctx: AbstractSyntaxTreeVisitor.VisitorContext[AbstractSyntax.Task]
    ): Unit = {
      if (ctx.element.input.isEmpty || ctx.element.input.get.declarations.isEmpty) {
        addEvent(ctx.element)
      }
    }
  }

  case class NoTaskOutputsRule(id: String,
                               severity: Severity,
                               version: WdlVersion,
                               events: mutable.Buffer[LintEvent],
                               docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {

    override def visitTask(
        ctx: AbstractSyntaxTreeVisitor.VisitorContext[AbstractSyntax.Task]
    ): Unit = {
      if (ctx.element.output.isEmpty || ctx.element.output.get.declarations.isEmpty) {
        addEvent(ctx.element)
      }
    }
  }

  val allRules: Map[String, LinterAstRuleApplySig] = Map(
      "A001" -> NonPortableTaskRule.apply,
      "A002" -> NoTaskInputsRule.apply,
      "A003" -> NoTaskOutputsRule.apply
  )
}
