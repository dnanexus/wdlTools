package wdlTools.exec

import java.nio.file.Files

import spray.json._
import wdlTools.eval.{
  Eval,
  EvalException,
  JsonSerializationException,
  Serialize,
  WdlValues,
  Context => EvalContext
}
import wdlTools.syntax.SourceLocation
import wdlTools.types.TypedAbstractSyntax.{
  InputDefinition,
  OptionalInputDefinition,
  OutputDefinition,
  OverridableInputDefinitionWithDefault,
  RequiredInputDefinition
}
import wdlTools.types.WdlTypes
import wdlTools.types.WdlTypes.T_Optional
import wdlTools.util.{FileSourceResolver, Logger}

/**
  * Implemention of the JSON Input Format in the WDL specification
  * https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#json-input-format.
  */
object InputOutput {

  /**
    * Converts a JSON input value with a type to a `WdlValues.V`. Only handles supported types
    * with constant values. Input formats that support alternative representations (e.g. object
    * value for File) must pre-process those values first. Does *not* perform file localization.
    * @param name: the variable name (for error reporting)
    * @param wdlType: the destination type
    * @param jsValue: the JSON value
    * @return the WDL value
    */
  def jsonToWdlValue(name: String,
                     wdlType: WdlTypes.T,
                     jsValue: JsValue,
                     loc: SourceLocation): WdlValues.V = {
    (wdlType, jsValue) match {
      // base case: primitive types
      case (WdlTypes.T_Boolean, JsBoolean(b)) => WdlValues.V_Boolean(b.booleanValue)
      case (WdlTypes.T_Int, JsNumber(bnm))    => WdlValues.V_Int(bnm.intValue)
      case (WdlTypes.T_Float, JsNumber(bnm))  => WdlValues.V_Float(bnm.doubleValue)
      case (WdlTypes.T_String, JsString(s))   => WdlValues.V_String(s)
      case (WdlTypes.T_File, JsString(s))     => WdlValues.V_File(s)

      // Maps. These are serialized as an object with a keys array and
      // a values array.
      case (WdlTypes.T_Map(keyType, valueType), _) =>
        val fields = jsValue.asJsObject.fields
        val m: Map[WdlValues.V, WdlValues.V] = fields.map {
          case (k: String, v: JsValue) =>
            val kWdl = jsonToWdlValue(s"${name}.${k}", keyType, JsString(k), loc)
            val vWdl = jsonToWdlValue(s"${name}.${k}", valueType, v, loc)
            kWdl -> vWdl
        }
        WdlValues.V_Map(m)

      // a few ways of writing a pair: an object, or an array
      case (WdlTypes.T_Pair(lType, rType), JsObject(fields))
          if Vector("left", "right").forall(fields.contains) =>
        val left = jsonToWdlValue(s"${name}.left", lType, fields("left"), loc)
        val right = jsonToWdlValue(s"${name}.right", rType, fields("right"), loc)
        WdlValues.V_Pair(left, right)

      case (WdlTypes.T_Pair(lType, rType), JsArray(Vector(l, r))) =>
        val left = jsonToWdlValue(s"${name}.left", lType, l, loc)
        val right = jsonToWdlValue(s"${name}.right", rType, r, loc)
        WdlValues.V_Pair(left, right)

      // empty array
      case (WdlTypes.T_Array(_, _), JsNull) =>
        WdlValues.V_Array(Vector.empty[WdlValues.V])

      // array
      case (WdlTypes.T_Array(t, _), JsArray(vec)) =>
        val wVec: Vector[WdlValues.V] = vec.zipWithIndex.map {
          case (elem: JsValue, index) =>
            jsonToWdlValue(s"${name}[${index}]", t, elem, loc)
        }
        WdlValues.V_Array(wVec)

      case (WdlTypes.T_Optional(_), JsNull) =>
        WdlValues.V_Null
      case (WdlTypes.T_Optional(t), jsv) =>
        val value = jsonToWdlValue(name, t, jsv, loc)
        WdlValues.V_Optional(value)

      // structs
      case (WdlTypes.T_Struct(structName, typeMap), JsObject(fields)) =>
        // convert each field
        val m = fields.map {
          case (key, value) =>
            val t: WdlTypes.T = typeMap(key)
            val elem: WdlValues.V = jsonToWdlValue(s"${name}.${key}", t, value, loc)
            key -> elem
        }
        WdlValues.V_Struct(structName, m)

      case _ =>
        throw new ExecException(
            s"Unsupported value ${jsValue.prettyPrint} for input ${name} with type ${wdlType}",
            loc
        )
    }
  }

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
          jsInputs.get(fqn).map(jsValue => jsonToWdlValue(fqn, wdlType, jsValue, inp.loc))
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
            case T_Optional(_) => true
            case _             => false
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

  def taskOutputToJson(outputs: Map[String, WdlValues.V],
                       taskName: String,
                       taskOutputDefinitions: Vector[OutputDefinition]): JsObject = {
    val fields: Map[String, JsValue] = taskOutputDefinitions.map { out =>
      val declName = out.name
      val key = s"${taskName}.${declName}"
      val value = {
        val wdlValue = outputs(declName)
        try {
          Serialize.toJson(wdlValue)
        } catch {
          case e: JsonSerializationException =>
            throw new ExecException(s"Error serializing value ${wdlValue} for output ${key}",
                                    e,
                                    out.loc)
        }
      }
      key -> value
    }.toMap
    JsObject(fields)
  }
}
