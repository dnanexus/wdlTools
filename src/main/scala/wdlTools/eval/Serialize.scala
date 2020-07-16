package wdlTools.eval

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.syntax.SourceLocation

// The mapping of JSON type to WDL type is:
// JSON Type 	WDL Type
// object 	Map[String, ?]
// array 	Array[?]
// number 	Int or Float
// string 	String
// boolean 	Boolean
// null 	null

object Serialize {
  def toJson(wv: V): JsValue = {
    wv match {
      case V_Null             => JsNull
      case V_Boolean(value)   => JsBoolean(value)
      case V_Int(value)       => JsNumber(value)
      case V_Float(value)     => JsNumber(value)
      case V_String(value)    => JsString(value)
      case V_File(value)      => JsString(value)
      case V_Directory(value) => JsString(value)

      // compound values
      case V_Array(vec) =>
        JsArray(vec.map(toJson))
      case V_Map(members) =>
        JsObject(members.map {
          case (k, v) =>
            val key = toJson(k) match {
              case JsString(value) => value
              case other =>
                throw new RuntimeException(s"Cannot serialize non-string map key ${other}")
            }
            key -> toJson(v)
        })
      case V_Object(members) =>
        JsObject(members.map { case (k, v) => k -> toJson(v) })
      case V_Struct(_, members) =>
        JsObject(members.map { case (k, v) => k -> toJson(v) })

      case other => throw new JsonSerializationException(s"value ${other} not supported")
    }
  }

  def toJson(wv: Map[String, WdlValues.V]): Map[String, JsValue] = {
    wv.view.mapValues(toJson).toMap
  }

  def fromJson(jsv: JsValue): V = {
    jsv match {
      case JsNull                               => V_Null
      case JsBoolean(value)                     => V_Boolean(value)
      case JsNumber(value) if value.isValidLong => V_Int(value.toLongExact)
      case JsNumber(value)                      => V_Float(value.toDouble)
      case JsString(value)                      => V_String(value)

      // compound values
      case JsArray(vec) =>
        V_Array(vec.map(fromJson))
      case JsObject(fields) =>
        V_Object(fields.map { case (k, v) => k -> fromJson(v) })
    }
  }

  @scala.annotation.tailrec
  def primitiveValueToString(wv: V, loc: SourceLocation): String = {
    wv match {
      case V_Null           => "null"
      case V_Boolean(value) => value.toString
      case V_Int(value)     => value.toString
      case V_Float(value)   => value.toString
      case V_String(value)  => value
      case V_File(value)    => value
      case V_Optional(x)    => primitiveValueToString(x, loc)
      case other =>
        throw new EvalException(s"prefix: ${other} is not a primitive value", loc)
    }
  }
}
