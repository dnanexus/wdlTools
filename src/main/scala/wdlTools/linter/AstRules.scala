package wdlTools.linter

import java.net.URL

import wdlTools.syntax.{AbstractSyntaxTreeVisitor, WdlVersion}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.AbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.util.Util.getFilename

import scala.collection.mutable

object AstRules {
  class LinterAstRule(conf: RuleConf, docSourceUrl: Option[URL], events: mutable.Buffer[LintEvent])
      extends AbstractSyntaxTreeVisitor {
    protected def addEvent(element: Element, message: Option[String] = None): Unit = {
      events.append(LintEvent(conf, element.text, docSourceUrl, message))
    }
  }

  type LinterAstRuleApplySig = (
      RuleConf,
      WdlVersion,
      mutable.Buffer[LintEvent],
      Option[URL]
  ) => LinterAstRule

  // rules ported from winstanley
  // did not port:
  // * missing command section - a command section is required, so the parser throws a SyntaxException if
  //   it doesn't find one
  // * value/callable lookup - these are caught by the type-checker
  // * no immediate declaration - the parser catches these
  // * wildcard outputs - the parser does not allow these even in draft-2
  // * unexpected/unsupplied inputs - the type-checker catches this

  case class NonPortableTaskRule(conf: RuleConf,
                                 version: WdlVersion,
                                 events: mutable.Buffer[LintEvent],
                                 docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
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

  case class NoTaskInputsRule(conf: RuleConf,
                              version: WdlVersion,
                              events: mutable.Buffer[LintEvent],
                              docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
    override def visitTask(
        ctx: VisitorContext[Task]
    ): Unit = {
      if (ctx.element.input.isEmpty || ctx.element.input.get.declarations.isEmpty) {
        addEvent(ctx.element)
      }
    }
  }

  case class NoTaskOutputsRule(conf: RuleConf,
                               version: WdlVersion,
                               events: mutable.Buffer[LintEvent],
                               docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {

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
    * Collisions between names that are allowed but confusing.
    */
  case class NameCollisionRule(conf: RuleConf,
                               version: WdlVersion,
                               events: mutable.Buffer[LintEvent],
                               docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {

    private val elements: mutable.Map[String, mutable.Set[Element]] = mutable.HashMap.empty

    override def visitName[P <: Element](name: String, parent: VisitorContext[P]): Unit = {
      if (!elements.contains(name)) {
        elements(name) = mutable.HashSet.empty[Element]
      }
      elements(name).add(parent.element)
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
  case class UnusedImportRule(conf: RuleConf,
                              version: WdlVersion,
                              events: mutable.Buffer[LintEvent],
                              docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
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
  case class ForwardReferenceRule(conf: RuleConf,
                                  version: WdlVersion,
                                  events: mutable.Buffer[LintEvent],
                                  docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      ctx.element match {
        case ExprIdentifier(id, text) => ()
        // TODO: waiting for referee in ExprIdentifier
        case _ => traverseExpression(ctx)
      }
    }
  }

  case class UnnecessaryQuantifierRule(conf: RuleConf,
                                       version: WdlVersion,
                                       events: mutable.Buffer[LintEvent],
                                       docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {}

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
    * TODO: enable user to configure ShellCheck path
    */
  case class ShellCheckRule(conf: RuleConf,
                            version: WdlVersion,
                            events: mutable.Buffer[LintEvent],
                            docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
    private val suppressions = Set(1009, 1072, 1083, 2043, 2050, 2157, 2193)

  }

  case class SelectArrayRule(conf: RuleConf,
                             version: WdlVersion,
                             events: mutable.Buffer[LintEvent],
                             docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {}

  /**
    * In Wdl2, only a specific set of runtime keys are allowed. In previous
    * versions, we check against a known set of keys and issue a warning for
    * any that don't match.
    */
  case class UnknownRuntimeKeyRule(conf: RuleConf,
                                   version: WdlVersion,
                                   events: mutable.Buffer[LintEvent],
                                   docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {}

  /**
    * Issue a warning for any version < 1.0.
    */
  case class MissingVersionRule(conf: RuleConf,
                                version: WdlVersion,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl, events) {
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
      "A004" -> NameCollisionRule.apply,
      "A005" -> UnusedImportRule.apply,
      "A006" -> ForwardReferenceRule.apply,
      "A007" -> UnnecessaryQuantifierRule.apply,
      "A008" -> ShellCheckRule.apply,
      "A009" -> SelectArrayRule.apply,
      "A010" -> UnknownRuntimeKeyRule.apply,
      "A011" -> MissingVersionRule.apply
  )
}
