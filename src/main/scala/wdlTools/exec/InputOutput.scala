package wdlTools.exec

import java.nio.file.Files
import spray.json._
import wdlTools.eval.{
  Eval,
  EvalException,
  WdlValueBindings,
  WdlValueSerde,
  WdlValueSerializationException,
  WdlValues,
  EvalUtils => WdlValueUtils
}
import wdlTools.syntax.SourceLocation
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.{ExprGraph, WdlTypes}
import dx.util.{Bindings, FileSourceResolver, LocalFileSource, Logger}

object InputOutput {
  def inputsFromValues(executableName: String,
                       inputParameters: Vector[InputParameter],
                       inputValues: Map[String, WdlValues.V],
                       evaluator: Eval,
                       ignoreDefaultEvalError: Boolean = true,
                       nullCollectionAsEmpty: Boolean = false,
                       logger: Logger = Logger.get): Bindings[String, WdlValues.V] = {
    // resolve default values for any missing inputs
    val init: Bindings[String, WdlValues.V] = WdlValueBindings.empty
    inputParameters.foldLeft(init) {
      case (ctx, decl) =>
        val value = decl match {
          case param: RequiredInputParameter if inputValues.contains(param.name) =>
            // ensure the required value is not T_Optional
            WdlValueUtils.unwrapOptional(inputValues(decl.name))
          case RequiredInputParameter(_, WdlTypes.T_Array(_, false), _) if nullCollectionAsEmpty =>
            // Special handling for required input Arrays that are non-optional but
            // allowed to be empty and do not have a value specified - set the value
            // to the empty array rather than throwing an exception.
            WdlValues.V_Array()
          case RequiredInputParameter(_, _: WdlTypes.T_Map, _) if nullCollectionAsEmpty =>
            // Special handling for required input Maps that are non-optional
            WdlValues.V_Map()
          case RequiredInputParameter(_, WdlTypes.T_Object, _) if nullCollectionAsEmpty =>
            // Special handling for required input Objects that are non-optional
            WdlValues.V_Object()
          case param: RequiredInputParameter =>
            throw new ExecException(
                s"Missing required input ${param.name} to executable ${executableName}",
                param.loc
            )
          case param if inputValues.contains(param.name) =>
            // ensure the optional value is T_Optional
            WdlValueUtils.ensureOptional(inputValues(param.name))
          case _: OptionalInputParameter =>
            WdlValues.V_Null
          case OverridableInputParameterWithDefault(name, wdlType, defaultExpr, loc) =>
            // An input definition that has a default value supplied.
            // Typical WDL example would be a declaration like: "Int x = 5"
            try {
              evaluator.applyExprAndCoerce(defaultExpr, wdlType, ctx)
            } catch {
              case e: EvalException if ignoreDefaultEvalError =>
                logger.trace(
                    s"Could not evaluate default value expression for input parameter ${name}",
                    exception = Some(e)
                )
                WdlValues.V_Null
              case t: Throwable =>
                throw new ExecException(
                    s"Could not evaluate default value expression for input parameter ${name}",
                    t,
                    loc
                )
            }
        }
        ctx.add(decl.name, value)
    }
  }

  def evaluateOutputs(outputParameters: Vector[OutputParameter],
                      evaluator: Eval,
                      ctx: WdlValueBindings): Bindings[String, WdlValues.V] = {
    val init: Bindings[String, WdlValues.V] = WdlValueBindings.empty
    outputParameters.foldLeft(init) {
      case (outCtx, OutputParameter(name, _, _, _)) if ctx.contains(name) =>
        outCtx.add(name, ctx.bindings(name))
      case (outCtx, OutputParameter(name, wdlType, expr, _)) =>
        outCtx.add(name, evaluator.applyExprAndCoerce(expr, wdlType, ctx.update(outCtx)))
    }
  }
}

/**
  * Implemention of the JSON Input Format in the WDL specification
  * https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#json-input-format.
  */
case class TaskInputOutput(task: Task, logger: Logger = Logger.Quiet) {
  private lazy val depOrder = ExprGraph.buildFrom(task)
  private lazy val inputParameters: Vector[InputParameter] = {
    val inputMap = task.inputs.map(i => i.name -> i).toMap
    depOrder.inputOrder.map(inputMap(_))
  }
  private lazy val outputParameters: Vector[OutputParameter] = {
    val outputMap = task.outputs.map(i => i.name -> i).toMap
    depOrder.outputOrder.map(outputMap(_))
  }

  def inputsFromValues(inputValues: Map[String, WdlValues.V],
                       evaluator: Eval,
                       ignoreDefaultEvalError: Boolean = true,
                       nullCollectionAsEmpty: Boolean = false): Bindings[String, WdlValues.V] = {
    InputOutput.inputsFromValues(task.name,
                                 inputParameters,
                                 inputValues,
                                 evaluator,
                                 ignoreDefaultEvalError,
                                 nullCollectionAsEmpty,
                                 logger)
  }

  def inputsFromJson(jsInputs: Map[String, JsValue],
                     evaluator: Eval,
                     strict: Boolean = false): Bindings[String, WdlValues.V] = {
    val values = inputParameters.flatMap { decl =>
      // lookup by fully-qualified name first, then plain name
      val fqn = s"${task.name}.${decl.name}"
      val value = if (jsInputs.contains(fqn)) {
        TaskInputOutput.deserialize(jsInputs(fqn), decl, fqn)
      } else if (jsInputs.contains(decl.name)) {
        TaskInputOutput.deserialize(jsInputs(decl.name), decl, decl.name)
      } else {
        None
      }
      value.map(decl.name -> _)
    }.toMap
    inputsFromValues(values, evaluator, strict)
  }

  def evaluateOutputs(evaluator: Eval, ctx: WdlValueBindings): Bindings[String, WdlValues.V] = {
    InputOutput.evaluateOutputs(outputParameters, evaluator, ctx)
  }

  def outputValuesToJson(outputs: Map[String, WdlValues.V],
                         prefixTaskName: Boolean = true): JsObject = {
    val fields: Map[String, JsValue] = outputParameters.map { decl =>
      val key = if (prefixTaskName) {
        s"${task.name}.${decl.name}"
      } else {
        decl.name
      }
      val value = {
        val wdlValue = outputs(decl.name)
        try {
          WdlValueSerde.serialize(wdlValue)
        } catch {
          case e: WdlValueSerializationException =>
            throw new ExecException(s"Error serializing value ${wdlValue} for output ${key}",
                                    e,
                                    decl.loc)
        }
      }
      key -> value
    }.toMap
    JsObject(fields)
  }

  def outputsToJson(evaluator: Eval,
                    ctx: WdlValueBindings,
                    prefixTaskName: Boolean = true): JsObject = {
    val outputValues = evaluateOutputs(evaluator, ctx)
    outputValuesToJson(outputValues.toMap, prefixTaskName)
  }
}

object TaskInputOutput {
  def deserialize(jsValue: JsValue, inputDef: InputParameter, name: String): Option[WdlValues.V] = {
    jsValue match {
      case null | JsNull =>
        // treat a value of null as undefined - this will get replaced with
        // a default value (or an exception will be thrown if this is a
        // required parameter)
        None
      case _ =>
        try {
          Some(WdlValueSerde.deserializeWithType(jsValue, inputDef.wdlType, name))
        } catch {
          case jse: WdlValueSerializationException =>
            throw new ExecException(jse.getMessage, inputDef.loc)
        }
    }
  }

  def resolveOutputValue(name: String,
                         wdlType: WdlTypes.T,
                         wdlValue: Option[WdlValues.V],
                         fileResolver: FileSourceResolver,
                         loc: SourceLocation,
                         optional: Boolean = false): WdlValues.V = {
    def resolveFile(address: String): WdlValues.V = {
      fileResolver.resolve(address) match {
        case local: LocalFileSource =>
          val path = local.canonicalPath
          if (Files.isDirectory(path)) {
            throw new ExecException(
                s"${address} is a directory for File output ${name}",
                loc
            )
          } else if (Files.exists(path)) {
            WdlValues.V_File(path.toString)
          } else if (optional) {
            WdlValues.V_Null
          } else {
            throw new ExecException(
                s"File ${address} does not exist for required output ${name}",
                loc
            )
          }
        case _ =>
          // this is a remote file source
          WdlValues.V_File(address)
      }
    }

    def resolveDirectory(address: String): WdlValues.V = {
      fileResolver.resolveDirectory(address) match {
        case local: LocalFileSource =>
          val path = local.canonicalPath
          if (Files.isDirectory(path)) {
            WdlValues.V_Directory(path.toString)
          } else if (Files.exists(path)) {
            throw new ExecException(
                s"${address} is a file for Directory output ${name}",
                loc
            )
          } else if (optional) {
            WdlValues.V_Null
          } else {
            throw new ExecException(
                s"Directory ${address} does not exist for required output ${name}",
                loc
            )
          }
        case _ =>
          // this is a remote file source
          WdlValues.V_File(address)
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
        resolveOutputValue(name, t, v, fileResolver, loc, optional = true)

      // allow missing/null conversion to empty array/map/object
      case (WdlTypes.T_Array(_, false), None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Array(Vector.empty)
      case (WdlTypes.T_Map(_, _), None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Map()
      case (WdlTypes.T_Object, None | Some(WdlValues.V_Null)) =>
        WdlValues.V_Object()
      case (WdlTypes.T_Struct(name, memberTypes), None | Some(WdlValues.V_Null))
          if memberTypes.values.forall {
            case WdlTypes.T_Optional(_) => true
            case _                      => false
          } =>
        WdlValues.V_Struct(name)

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
            resolveOutputValue(s"${name}[${idx}]",
                               t,
                               Some(item),
                               fileResolver,
                               loc,
                               optional = optional)
        })
      case (WdlTypes.T_Map(keyType, valueType), Some(WdlValues.V_Map(members))) =>
        WdlValues.V_Map(members.map {
          case (k, v) =>
            val key = resolveOutputValue(
                s"${name}.${k}",
                keyType,
                Some(k),
                fileResolver,
                loc,
                optional = optional
            )
            val value = resolveOutputValue(
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
            k -> resolveOutputValue(s"${name}.${k}",
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
            memberTypes
              .map {
                case (memberName, memberType) =>
                  memberName -> resolveOutputValue(s"${name}.${memberName}",
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
