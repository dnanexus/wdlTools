package wdlTools.exec

import spray.json._
import wdlTools.eval.{Eval, EvalException, WdlValues, Context => EvalContext}
import wdlTools.types.TypedAbstractSyntax.{
  InputDefinition,
  OptionalInputDefinition,
  OutputDefinition,
  OverridableInputDefinitionWithDefault,
  RequiredInputDefinition
}
import wdlTools.types.WdlTypes
import wdlTools.util.Logger

/**
  * Implemention of the JSON Input Format in the WDL specification
  * https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#json-input-format.
  */
object InputOutput {
  def taskInputFromJson(jsInputs: Map[String, JsValue],
                        taskName: String,
                        taskInputDefinitions: Vector[InputDefinition],
                        evaluator: Eval,
                        logger: Logger = Logger.Quiet,
                        strict: Boolean = false): EvalContext = {
    val taskInputs = taskInputDefinitions.map(inp => inp.name -> inp).toMap
    val typesAndValues: Map[String, (WdlTypes.T, Option[WdlValues.V])] = taskInputs.map {
      case (declName, inp) =>
        val fqn = s"${taskName}.${declName}"
        val wdlType = inp.wdlType
        val wdlValue: Option[WdlValues.V] =
          jsInputs.get(fqn).map(jsValue => Util.jsonToWdlValue(fqn, wdlType, jsValue, inp.loc))
        declName -> (wdlType, wdlValue)
    }
    val (defined, undefined) = typesAndValues.partition(_._2._2.isDefined)
    val definedContext = EvalContext(defined.map {
      case (declName, (_, Some(wdlValue))) => declName -> wdlValue
    })
    if (undefined.isEmpty) {
      definedContext
    } else {
      // Evaluate defaults for missing values
      // TODO: this could be done better by ordering the expressions based on their dependencies
      undefined.keys.foldLeft(definedContext) {
        case (ctx, declName) =>
          val wdlValue: WdlValues.V = taskInputs(declName) match {
            case inp: RequiredInputDefinition =>
              throw new ExecException(s"Missing required input ${declName} to task ${taskName}",
                                      inp.loc)
            case _: OptionalInputDefinition => WdlValues.V_Null
            case OverridableInputDefinitionWithDefault(_, _, defaultExpr, loc) =>
              try {
                evaluator.applyExpr(defaultExpr, ctx)
              } catch {
                case e: EvalException if !strict =>
                  logger.trace(
                      s"Could not evaluate default value expression for input parameter ${declName}",
                      exception = Some(e)
                  )
                  WdlValues.V_Null
                case t: Throwable =>
                  throw new ExecException(
                      s"Could not evaluate default value expression for input parameter ${declName}",
                      t,
                      loc
                  )
              }
          }
          ctx.addBinding(declName, wdlValue)
      }
    }
  }

  def taskOutputToJson(outputContext: EvalContext,
                       taskName: String,
                       taskOutputDefinitions: Vector[OutputDefinition],
                       strict: Boolean = true): JsObject = {
    val fields: Map[String, JsValue] = taskOutputDefinitions.map { out =>
      val declName = out.name
      val key = s"${taskName}.${declName}"
      val value =
        Util.wdlValueToJson(out.name,
                            out.wdlType,
                            outputContext.bindings.get(declName),
                            strict,
                            out.loc)
      key -> value
    }.toMap
    JsObject(fields)
  }
}
