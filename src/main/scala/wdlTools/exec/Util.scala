package wdlTools.exec

import spray.json._
import wdlTools.eval.Serialize.toJson
import wdlTools.eval.WdlValues
import wdlTools.syntax.SourceLocation
import wdlTools.types.WdlTypes

object Util {

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

  @scala.annotation.tailrec
  def wdlValueToJson(name: String,
                     wdlType: WdlTypes.T,
                     wdlValue: Option[WdlValues.V],
                     strict: Boolean = true,
                     loc: SourceLocation): JsValue = {
    (wdlType, wdlValue) match {
      case (WdlTypes.T_Optional(_), None | Some(WdlValues.V_Null)) => JsNull
      case (WdlTypes.T_Optional(t), v)                             => wdlValueToJson(name, t, v, strict, loc)

      // allow missing/null conversion to empty array/map/object if we're not being strict
      case (WdlTypes.T_Array(_, _), None) if !strict                   => JsArray()
      case (WdlTypes.T_Array(_, _), Some(WdlValues.V_Null)) if !strict => JsArray()
      case (WdlTypes.T_Map(_, _), None) if !strict                     => JsObject()
      case (WdlTypes.T_Map(_, _), Some(WdlValues.V_Null)) if !strict   => JsObject()
      case (WdlTypes.T_Object, None) if !strict                        => JsObject()
      case (WdlTypes.T_Object, Some(WdlValues.V_Null)) if !strict      => JsObject()

      // None/null not allowed for any other cases
      case (_, None | Some(WdlValues.V_Null)) =>
        throw new ExecException(
            s"Non-optional field ${name} has empty/null WDL value",
            loc
        )

      // Otherwise just do default serialization
      case (_, Some(v)) => toJson(v)
    }
  }
}
