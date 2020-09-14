package wdlTools.exec

import java.nio.file.Files

import spray.json._
import wdlTools.eval.{
  Eval,
  EvalException,
  JsonSerializationException,
  WdlValueBindings,
  WdlValueSerde,
  WdlValues,
  Utils => WdlValueUtils
}
import wdlTools.syntax.SourceLocation
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.{ExprGraph, WdlTypes}
import wdlTools.util.{FileSourceResolver, Logger}

abstract class InputOutput(callable: Callable, logger: Logger) {
  protected lazy val callableInputs: Map[String, InputDefinition] =
    callable.inputs.map(inp => inp.name -> inp).toMap
  protected lazy val callableOutputs: Map[String, OutputDefinition] =
    callable.outputs.map(out => out.name -> out).toMap

  protected def inputOrder: Vector[String]

  protected def outputOrder: Vector[String]

  def inputsFromValues(inputValues: Map[String, WdlValues.V],
                       evaluator: Eval,
                       strict: Boolean = false): WdlValueBindings = {
    // resolve default values for any missing inputs
    inputOrder.foldLeft(WdlValueBindings.empty) {
      case (ctx, declName) =>
        val value = callableInputs(declName) match {
          case _: RequiredInputDefinition if inputValues.contains(declName) =>
            // ensure the required value is not T_Optional
            WdlValueUtils.unwrapOptional(inputValues(declName))
          case inp: RequiredInputDefinition =>
            throw new ExecException(s"Missing required input ${declName} to task ${callable.name}",
                                    inp.loc)
          case _ if inputValues.contains(declName) =>
            // ensure the optional value is T_Optional
            WdlValueUtils.ensureOptional(inputValues(declName))
          case _: OptionalInputDefinition =>
            WdlValues.V_Null
          case OverridableInputDefinitionWithDefault(_, wdlType, defaultExpr, loc) =>
            // An input definition that has a default value supplied.
            // Typical WDL example would be a declaration like: "Int x = 5"
            try {
              evaluator.applyExprAndCoerce(defaultExpr, wdlType, ctx)
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
        ctx.add(declName, value)
    }
  }

  def evaluateOutputs(evaluator: Eval, ctx: WdlValueBindings): WdlValueBindings = {
    outputOrder.foldLeft(WdlValueBindings.empty) {
      case (outCtx, declName) =>
        val out = callableOutputs(declName)
        outCtx.add(declName,
                   evaluator.applyExprAndCoerce(out.expr, out.wdlType, ctx.update(outCtx)))
    }
  }
}

/**
  * Implemention of the JSON Input Format in the WDL specification
  * https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#json-input-format.
  */
case class TaskInputOutput(task: Task, logger: Logger = Logger.Quiet)
    extends InputOutput(task, logger) {
  private lazy val depOrder = ExprGraph.buildFrom(task)

  override protected def inputOrder: Vector[String] = depOrder.inputOrder

  override protected def outputOrder: Vector[String] = depOrder.outputOrder

  def inputsFromJson(jsInputs: Map[String, JsValue],
                     evaluator: Eval,
                     strict: Boolean = false): WdlValueBindings = {
    val values = callableInputs.flatMap {
      case (declName, inp) =>
        // lookup by fully-qualified name first, then plain name
        val fqn = s"${task.name}.${declName}"
        val value = if (jsInputs.contains(fqn)) {
          TaskInputOutput.deserialize(jsInputs(fqn), inp, fqn)
        } else if (jsInputs.contains(declName)) {
          TaskInputOutput.deserialize(jsInputs(declName), inp, declName)
        } else {
          None
        }
        value.map(declName -> _)
    }
    inputsFromValues(values, evaluator, strict)
  }

  def outputValuesToJson(outputs: Map[String, WdlValues.V],
                         prefixTaskName: Boolean = true): JsObject = {
    val fields: Map[String, JsValue] = callableOutputs.map {
      case (declName, out) =>
        val key = if (prefixTaskName) {
          s"${task.name}.${declName}"
        } else {
          declName
        }
        val value = {
          val wdlValue = outputs(declName)
          try {
            WdlValueSerde.serialize(wdlValue)
          } catch {
            case e: JsonSerializationException =>
              throw new ExecException(s"Error serializing value ${wdlValue} for output ${key}",
                                      e,
                                      out.loc)
          }
        }
        key -> value
    }
    JsObject(fields)
  }

  def outputsToJson(evaluator: Eval,
                    ctx: WdlValueBindings,
                    prefixTaskName: Boolean = true): JsObject = {
    val outputValues: WdlValueBindings = evaluateOutputs(evaluator, ctx)
    outputValuesToJson(outputValues.toMap, prefixTaskName)
  }
}

object TaskInputOutput {
  def deserialize(jsValue: JsValue,
                  inputDef: InputDefinition,
                  name: String): Option[WdlValues.V] = {
    jsValue match {
      case null | JsNull =>
        // treat a value of null as undefined - this will get replaced with
        // a default value (or an exception will be thrown if this is a
        // required parameter)
        None
      case _ =>
        try {
          Some(WdlValueSerde.deserialize(jsValue, inputDef.wdlType, name))
        } catch {
          case jse: JsonSerializationException =>
            throw new ExecException(jse.getMessage, inputDef.loc)
        }
    }
  }

  def resolveWdlValue(name: String,
                      wdlType: WdlTypes.T,
                      wdlValue: Option[WdlValues.V],
                      fileResolver: FileSourceResolver,
                      loc: SourceLocation,
                      optional: Boolean = false): WdlValues.V = {
    def resolveFile(path: String): WdlValues.V = {
      val resolved = fileResolver.resolve(path).localPath
      if (Files.isDirectory(resolved)) {
        throw new ExecException(
            s"${path} is a directory for File output ${name}",
            loc
        )
      } else if (Files.exists(resolved)) {
        WdlValues.V_File(resolved.toString)
      } else if (optional) {
        WdlValues.V_Null
      } else {
        throw new ExecException(
            s"File ${path} does not exist for required output ${name}",
            loc
        )
      }
    }

    def resolveDirectory(path: String): WdlValues.V = {
      val resolved = fileResolver.resolveDirectory(path).localPath
      if (Files.isDirectory(resolved)) {
        WdlValues.V_Directory(resolved.toString)
      } else if (Files.exists(resolved)) {
        throw new ExecException(
            s"${path} is a file for Directory output ${name}",
            loc
        )
      } else if (optional) {
        WdlValues.V_Null
      } else {
        throw new ExecException(
            s"Directory ${path} does not exist for required output ${name}",
            loc
        )
      }
    }

    (wdlType, wdlValue) match {
      // null values
      case (WdlTypes.T_Optional(_), None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Null
      case (WdlTypes.T_Any, None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Null

      // unwrap non-null value of optional type
      case (WdlTypes.T_Optional(t), v) =>
        resolveWdlValue(name, t, v, fileResolver, loc, optional = true)

      // allow missing/null conversion to empty array/map/object
      case (WdlTypes.T_Array(_, false), None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Array(Vector.empty)
      case (WdlTypes.T_Map(_, _), None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Map(Map.empty)
      case (WdlTypes.T_Object, None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Object(Map.empty)
      case (WdlTypes.T_Struct(name, memberTypes), None | Some(WdlValues.V_Null))
          if memberTypes.values.forall {
            case WdlTypes.T_Optional(_) => true
            case _                      => false
          } =>
        WdlValues.V_Struct(name, Map.empty)

      // None/null not allowed for any other cases
      case (_, None | Some(WdlValues.V_Null)) =>
        throw new ExecException(
            s"Non-optional field ${name} has empty/null WDL value",
            loc
        )

      // primitive values
      case (WdlTypes.T_Boolean | WdlTypes.T_Any, Some(b: WdlValues.V_Boolean)) => b
      case (WdlTypes.T_Int | WdlTypes.T_Any, Some(i: WdlValues.V_Int))         => i
      case (WdlTypes.T_Float | WdlTypes.T_Any, Some(f: WdlValues.V_Float))     => f
      case (WdlTypes.T_String | WdlTypes.T_Any, Some(s: WdlValues.V_String))   => s
      case (WdlTypes.T_File | WdlTypes.T_Any, Some(WdlValues.V_File(path))) =>
        resolveFile(path)
      case (WdlTypes.T_File, Some(WdlValues.V_String(path))) =>
        resolveFile(path)
      case (WdlTypes.T_Directory | WdlTypes.T_Any, Some(WdlValues.V_Directory(path))) =>
        resolveDirectory(path)
      case (WdlTypes.T_Directory, Some(WdlValues.V_String(path))) =>
        resolveDirectory(path)

      // compound values
      case (WdlTypes.T_Array(t, _), Some(WdlValues.V_Array(vec))) =>
        WdlValues.V_Array(vec.zipWithIndex.map {
          case (item, idx) =>
            resolveWdlValue(s"${name}[${idx}]",
                            t,
                            Some(item),
                            fileResolver,
                            loc,
                            optional = optional)
        })
      case (WdlTypes.T_Map(keyType, valueType), Some(WdlValues.V_Map(members))) =>
        WdlValues.V_Map(members.map {
          case (k, v) =>
            val key = resolveWdlValue(
                s"${name}.${k}",
                keyType,
                Some(k),
                fileResolver,
                loc,
                optional = optional
            )
            val value = resolveWdlValue(
                s"${name}.${k}",
                valueType,
                Some(v),
                fileResolver,
                loc,
                optional = optional
            )
            key -> value
        })
      case (_, Some(WdlValues.V_Object(members))) =>
        WdlValues.V_Object(members.map {
          case (k, v) =>
            k -> resolveWdlValue(s"${name}.${k}",
                                 WdlTypes.T_Any,
                                 Some(v),
                                 fileResolver,
                                 loc,
                                 optional = optional)
        })
      case (WdlTypes.T_Struct(structName, memberTypes),
            Some(WdlValues.V_Struct(_, memberValues))) =>
        WdlValues.V_Struct(
            structName,
            memberTypes.map {
              case (memberName, memberType) =>
                memberName -> resolveWdlValue(s"${name}.${memberName}",
                                              memberType,
                                              memberValues.get(memberName),
                                              fileResolver,
                                              loc,
                                              optional = optional)

            }
        )

      case _ =>
        throw new ExecException(
            s"Unsupported conversion of ${wdlType} ${wdlValue} to JSON",
            loc
        )
    }
  }
}
