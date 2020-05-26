package wdlTools.linter

import java.net.URL

import wdlTools.syntax.{AbstractSyntaxTreeVisitor, WdlVersion}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.AbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.util.Util.getFilename

import scala.collection.mutable

object AbstractSyntaxTreeRules {
  class LinterAstRule(conf: RuleConf, docSourceUrl: Option[URL]) extends AbstractSyntaxTreeVisitor {
    private var events: Vector[LintEvent] = Vector.empty

    def getEvents: Vector[LintEvent] = events

    protected def addEvent(ctx: VisitorContext[_ <: Element],
                           message: Option[String] = None): Unit = {
      addEventFromElement(ctx.element, message)
    }

    protected def addEventFromElement(element: Element, message: Option[String] = None): Unit = {
      events :+= LintEvent(conf, element.text, docSourceUrl, message)
    }
  }

  type LinterAstRuleApplySig = (RuleConf, WdlVersion, Option[URL]) => LinterAstRule

  // rules ported from winstanley
  // did not port:
  // * missing command section - a command section is required, so the parser throws a SyntaxException if
  //   it doesn't find one
  // * value/callable lookup - these are caught by the type-checker
  // * no immediate declaration - the parser catches these
  // * wildcard outputs - the parser does not allow these even in draft-2
  // * unexpected/unsupplied inputs - the type-checker catches this

  case class NonPortableTaskRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {
    private val containerKeys = Set("docker", "container")

    override def visitTask(
        ctx: VisitorContext[Task]
    ): Unit = {
      if (ctx.element.runtime.isEmpty) {
        addEvent(ctx, Some("add a runtime section specifying a container"))
      } else if (!ctx.element.runtime.get.kvs.exists(kv => containerKeys.contains(kv.id))) {
        addEvent(ctx, Some("add a container to the runtime section"))
      }
    }
  }

  case class NoTaskInputsRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {
    override def visitTask(
        ctx: VisitorContext[Task]
    ): Unit = {
      if (ctx.element.input.isEmpty || ctx.element.input.get.declarations.isEmpty) {
        addEvent(ctx)
      }
    }
  }

  case class NoTaskOutputsRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {

    override def visitTask(
        ctx: VisitorContext[Task]
    ): Unit = {
      if (ctx.element.output.isEmpty || ctx.element.output.get.declarations.isEmpty) {
        addEvent(ctx)
      }
    }
  }

  // rules ported from miniwdl
  // rules not ported:
  // * ForwardReference: this is a TypeException
  // * IncompleteCall: this is a TypeException

  /**
    * Collisions between names that are allowed but confusing.
    */
  case class NameCollisionRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {

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
      elements.values.filter(_.size > 1).flatten.foreach(e => addEventFromElement(e))
    }
  }

  /**
    * A file is imported but never used
    */
  case class UnusedImportRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {
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
          addEventFromElement(imp, Some("import"))
        }
      unusedAliases.map(_._2._2).foreach { alias =>
        addEventFromElement(alias, Some("alias"))
      }
    }

    override def visitDataType(ctx: VisitorContext[Type]): Unit = {
      ctx.element match {
        case TypeIdentifier(id, _)  => usedTypeNames.add(id)
        case TypeStruct(name, _, _) => usedTypeNames.add(name)
        case _                      => ()
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      ctx.element.name match {
        case dottedNameRegexp(namespace) => usedImportNames.add(namespace)
      }
    }
  }

  /**
    * In Wdl2, only a specific set of runtime keys are allowed. In previous
    * versions, we check against a known set of keys and issue a warning for
    * any that don't match.
    */
  case class UnknownRuntimeKeyRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(if (version >= WdlVersion.V2) {
        // Invalid runtime key is always an error for WDL 2+
        // TODO: If this ends up being a parse error then we don't have to check for it here
        conf.copy(severity = Severity.Error)
      } else {
        conf
      }, docSourceUrl) {

    private val keys = Set(
        "container",
        "cpu",
        "memory",
        "gpu",
        "disks",
        "maxRetries",
        "returnCodes"
    ) ++ (if (version <= WdlVersion.V1) {
            Set(
                "bootDiskSizeGb",
                "continueOnReturnCode",
                "cpuPlatform",
                "disk",
                "docker",
                "dockerWorkingDir",
                "dx_instance_type",
                "gpuCount",
                "gpuType",
                "noAddress",
                "preemptible",
                "queueArn",
                "time",
                "zones"
            )
          } else None)

    override def visitRuntimeKV(ctx: VisitorContext[RuntimeKV]): Unit = {
      if (!keys.contains(ctx.element.id)) {
        addEvent(ctx)
      }
    }
  }

  /**
    * Issue a warning for any version < 1.0.
    */
  case class MissingVersionRule(conf: RuleConf, version: WdlVersion, docSourceUrl: Option[URL])
      extends LinterAstRule(conf, docSourceUrl) {
    override def visitDocument(ctx: VisitorContext[Document]): Unit = {
      if (version < WdlVersion.V1) {
        addEvent(ctx)
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
      "A006" -> UnknownRuntimeKeyRule.apply,
      "A007" -> MissingVersionRule.apply
  )
}
