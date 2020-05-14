package wdlTools.linter

import java.net.URL

import wdlTools.linter.Severity.Severity
import wdlTools.syntax.{AbstractSyntaxTreeVisitor, WdlVersion}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.AbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.util.Util.getFilename

import scala.collection.mutable

object AstRules {
  class LinterAstRule(id: String,
                      severity: Severity,
                      docSourceUrl: Option[URL],
                      events: mutable.Buffer[LintEvent])
      extends AbstractSyntaxTreeVisitor {
    protected def addEvent(element: Element, message: Option[String] = None): Unit = {
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
        ctx: VisitorContext[Task]
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
        ctx: VisitorContext[Task]
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
        ctx: VisitorContext[Task]
    ): Unit = {
      if (ctx.element.output.isEmpty || ctx.element.output.get.declarations.isEmpty) {
        addEvent(ctx.element)
      }
    }
  }

  // rules ported from miniwdl

  /**
    * Call without all required inputs.
    */
  case class IncompleteCallRule(id: String,
                                severity: Severity,
                                version: WdlVersion,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      // TODO: can't implement this until callee inputs are provided by Call
    }
  }

  /**
    * Collisions between names that are allowed but confusing.
    */
  case class NameCollisionRule(id: String,
                               severity: Severity,
                               version: WdlVersion,
                               events: mutable.Buffer[LintEvent],
                               docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {

    // TODO: Can we do this with visitIdentifier instead?

    private val elements: mutable.Map[String, mutable.Set[Element]] = mutable.HashMap.empty

    private def addElement(name: String, element: Element): Unit = {
      if (!elements.contains(name)) {
        elements(name) = mutable.HashSet.empty[Element]
      }
      elements(name).add(element)
    }

    override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
      val name = ctx.element.name.map(_.value).getOrElse(getFilename(ctx.element.addr.value))
      addElement(name, ctx.element)
      ctx.element.aliases.foreach(a => addElement(a.id2, a))
    }

    override def visitStruct(ctx: VisitorContext[TypeStruct]): Unit = {
      addElement(ctx.element.name, ctx.element)
    }

    override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
      addElement(ctx.element.name, ctx.element)
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      addElement(ctx.element.alias.map(_.name).getOrElse(ctx.element.name), ctx.element)
    }

    override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
      addElement(ctx.element.identifier, ctx.element)
    }

    override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
      addElement(ctx.element.name, ctx.element)
    }

    override def visitTask(ctx: VisitorContext[Task]): Unit = {
      addElement(ctx.element.name, ctx.element)
    }

    override def visitDocument(ctx: VisitorContext[Document]): Unit = {
      // Collect all names
      super.visitDocument(ctx)
      // Add events for any collisions
      elements.values.filter(_.size > 1).flatten.foreach(e => addEvent(e))
    }
  }

  /**
    * A file is imported but never used
    */
  case class UnusedImportRule(id: String,
                              severity: Severity,
                              version: WdlVersion,
                              events: mutable.Buffer[LintEvent],
                              docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    private val dottedNameRegexp = "(.*?)\\..+".r
    private val usedImportNames: mutable.Set[String] = mutable.HashSet.empty
    private val usedTypeNames: mutable.Set[String] = mutable.HashSet.empty

    override def visitDocument(ctx: VisitorContext[Document]): Unit = {
      super.visitDocument(ctx)
      // compare used names to import names/aliases
      val allImports: Map[String, ImportDoc] = ctx.element.elements.collect {
        case imp: ImportDoc =>
          imp.name.map(_.value).getOrElse(getFilename(imp.addr.value)) -> imp
      }.toMap
      val allImportAliases: Map[String, (String, ImportAlias)] = allImports.flatMap {
        case (name, imp) => imp.aliases.map(alias => alias.id2 -> (name, alias))
      }
      val (usedAliases, unusedAliases) =
        allImportAliases.partition(x => usedTypeNames.contains(x._1))
      allImports.view
        .filterKeys(usedImportNames.toSet ++ usedAliases.map(_._2._1).toSet)
        .values
        .foreach { imp =>
          addEvent(imp, Some("import"))
        }
      unusedAliases.map(_._2._2).foreach { alias =>
        addEvent(alias, Some("alias"))
      }
    }

    override def visitDataType(ctx: VisitorContext[Type]): Unit = {
      ctx.element match {
        case TypeIdentifier(id, _)  => usedTypeNames.add(id)
        case TypeStruct(name, _, _) => usedTypeNames.add(name)
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      ctx.element.name match {
        case dottedNameRegexp(namespace) => usedImportNames.add(namespace)
      }
    }
  }

  /**
    * Identifier preceeding the declaration/call that it references.
    */
  case class ForwardReferenceRule(id: String,
                                  severity: Severity,
                                  version: WdlVersion,
                                  events: mutable.Buffer[LintEvent],
                                  docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {}
  }

  case class UnusedDeclarationRule(id: String,
                                   severity: Severity,
                                   version: WdlVersion,
                                   events: mutable.Buffer[LintEvent],
                                   docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {}

  case class UnusedCallRule(id: String,
                            severity: Severity,
                            version: WdlVersion,
                            events: mutable.Buffer[LintEvent],
                            docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {}

  case class UnnecessaryQuantifierRule(id: String,
                                       severity: Severity,
                                       version: WdlVersion,
                                       events: mutable.Buffer[LintEvent],
                                       docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {}

  /**
    * If ShellCheck is installed, run it on task commands and propagate any
    * lint it finds.
    * we suppress
    *   SC1083 This {/} is literal
    *   SC2043 This loop will only ever run once for a constant value
    *   SC2050 This expression is constant
    *   SC2157 Argument to -n is always true due to literal strings
    *   SC2193 The arguments to this comparison can never be equal
    * which can be triggered by dummy values we substitute to write the script
    * also SC1009 and SC1072 are non-informative commentary
    *
    */
  case class ShellCheckRule(id: String,
                            severity: Severity,
                            version: WdlVersion,
                            events: mutable.Buffer[LintEvent],
                            docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    private val suppressions = Set(1009, 1072, 1083, 2043, 2050, 2157, 2193)

  }

  case class SelectArrayRule(id: String,
                             severity: Severity,
                             version: WdlVersion,
                             events: mutable.Buffer[LintEvent],
                             docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {}

  /**
    * In Wdl2, only a specific set of runtime keys are allowed. In previous
    * versions, we check against a known set of keys and issue a warning for
    * any that don't match.
    */
  case class UnknownRuntimeKeyRule(id: String,
                                   severity: Severity,
                                   version: WdlVersion,
                                   events: mutable.Buffer[LintEvent],
                                   docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {}

  /**
    * Issue a warning for any version < 1.0.
    */
  case class MissingVersionRule(id: String,
                                severity: Severity,
                                version: WdlVersion,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterAstRule(id, severity, docSourceUrl, events) {
    override def visitDocument(ctx: VisitorContext[Document]): Unit = {
      if (version < WdlVersion.V1) {
        addEvent(ctx.element)
      }
    }
  }

  // TODO: load these dynamically from a file
  val allRules: Map[String, LinterAstRuleApplySig] = Map(
      "A001" -> NonPortableTaskRule.apply,
      "A002" -> NoTaskInputsRule.apply,
      "A003" -> NoTaskOutputsRule.apply,
      "A004" -> IncompleteCallRule.apply,
      "A005" -> NameCollisionRule.apply,
      "A006" -> UnusedImportRule.apply,
      "A007" -> ForwardReferenceRule.apply,
      "A008" -> UnusedDeclarationRule.apply,
      "A009" -> UnusedCallRule.apply,
      "A010" -> UnnecessaryQuantifierRule.apply,
      "A011" -> ShellCheckRule.apply,
      "A012" -> SelectArrayRule.apply,
      "A013" -> UnknownRuntimeKeyRule.apply,
      "A014" -> MissingVersionRule.apply
  )
}
