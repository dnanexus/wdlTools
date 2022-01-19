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
case class RuntimeAttributes(runtime: Option[Runtime] = None,
                             hints: Option[Hints] = None,
                             overrideRuntimeValues: Option[VBindings] = None,
                             overrideHintValues: Option[VBindings] = None,
                             defaultValues: Option[VBindings] = None) {
  def contains(id: String): Boolean = {
    overrideRuntimeValues.exists(_.contains(id)) ||
    overrideHintValues.exists(_.contains(id)) ||
    runtime.exists(_.contains(id)) ||
    hints.exists(_.contains(id)) ||
    defaultValues.exists(_.contains(id))
  }

  def get(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    overrideRuntimeValues
      .flatMap(_.get(id, wdlTypes))
      .orElse(overrideHintValues.flatMap(_.get(id, wdlTypes)))
      .orElse(
          Option
            .when(runtime.exists(_.allows(id)))(runtime.get.get(id, wdlTypes))
            .flatten
            .orElse(hints.flatMap(_.get(id, wdlTypes)))
            .orElse(defaultValues.flatMap(_.get(id, wdlTypes)))
      )
  }

  def containsRuntime(id: String): Boolean = {
    overrideRuntimeValues.exists(_.contains(id)) ||
    runtime.exists(_.contains(id)) ||
    defaultValues.exists(_.contains(id))
  }

  def getRuntime(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    overrideRuntimeValues
      .flatMap(_.get(id, wdlTypes))
      .orElse(
          Option
            .when(runtime.exists(_.allows(id)))(runtime.get.get(id, wdlTypes))
            .flatten
            .orElse(defaultValues.flatMap(_.get(id, wdlTypes)))
      )
  }

  def containsHint(id: String): Boolean = {
    overrideHintValues.exists(_.contains(id)) ||
    hints.exists(_.contains(id)) ||
    defaultValues.exists(_.contains(id))
  }

  def getHint(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    overrideHintValues
      .flatMap(_.get(id, wdlTypes))
      .orElse(hints.flatMap(_.get(id, wdlTypes)))
      .orElse(defaultValues.flatMap(_.get(id, wdlTypes)))
  }
}

object RuntimeAttributes {
  def fromTask(
      task: TAT.Task,
      evaluator: Eval,
      ctx: Option[WdlValueBindings] = None,
      overrideRuntimeValues: Option[VBindings] = None,
      overrideHintValues: Option[VBindings] = None,
      defaultValues: Option[VBindings] = None
  ): RuntimeAttributes = {
    create(task.runtime,
           task.hints,
           evaluator,
           ctx,
           overrideRuntimeValues,
           overrideHintValues,
           defaultValues,
           Some(task.loc))
  }

  def create(
      runtimeSection: Option[RuntimeSection],
      hintsSection: Option[MetaSection],
      evaluator: Eval,
      ctx: Option[WdlValueBindings] = None,
      overrideRuntimeValues: Option[VBindings] = None,
      overrideHintValues: Option[VBindings] = None,
      defaultValues: Option[VBindings] = None,
      sourceLocation: Option[SourceLocation] = None
  ): RuntimeAttributes = {
    val runtime = runtimeSection.map(r =>
      Runtime.create(Some(r), evaluator, ctx, runtimeLocation = sourceLocation))
    val hints = hintsSection.map(h => Hints.create(Some(h)))
    RuntimeAttributes(runtime, hints, overrideRuntimeValues, overrideHintValues, defaultValues)
  }
}
