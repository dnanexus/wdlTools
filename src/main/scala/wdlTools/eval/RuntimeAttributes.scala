package wdlTools.eval

import wdlTools.syntax.SourceLocation
import wdlTools.types.TypedAbstractSyntax.{MetaSection, RuntimeSection}
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}

/**
  * Unification of runtime and hints sections, to enable accessing runtime attributes in
  * a version-independent manner.
  * @param runtime runtime section
  * @param hints hints section
  */
case class RuntimeAttributes(runtime: Option[Runtime],
                             hints: Option[Hints],
                             defaultValues: WdlValueBindings) {
  def contains(id: String): Boolean = {
    runtime.exists(_.contains(id)) || hints.exists(_.contains(id)) || defaultValues.contains(id)
  }

  def get(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    val value = if (runtime.exists(_.allows(id))) {
      runtime.get.get(id, wdlTypes)
    } else {
      None
    }
    value
      .orElse(hints.flatMap(_.get(id, wdlTypes)))
      .orElse(defaultValues.get(id, wdlTypes))
  }
}

object RuntimeAttributes {
  def fromTask(
      task: TAT.Task,
      evaluator: Eval,
      ctx: Option[WdlValueBindings] = None,
      defaultValues: WdlValueBindings = WdlValueBindings.empty
  ): RuntimeAttributes = {
    create(task.runtime, task.hints, evaluator, ctx, defaultValues, Some(task.loc))
  }

  def create(
      runtimeSection: Option[RuntimeSection],
      hintsSection: Option[MetaSection],
      evaluator: Eval,
      ctx: Option[WdlValueBindings] = None,
      defaultValues: WdlValueBindings = WdlValueBindings.empty,
      sourceLocation: Option[SourceLocation] = None
  ): RuntimeAttributes = {
    val runtime = runtimeSection.map(r =>
      Runtime.create(Some(r), evaluator, ctx, runtimeLocation = sourceLocation)
    )
    val hints = hintsSection.map(h => Hints.create(Some(h)))
    RuntimeAttributes(runtime, hints, defaultValues)
  }
}
