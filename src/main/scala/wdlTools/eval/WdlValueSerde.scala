package wdlTools.eval

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.types.WdlTypes._
import dx.util.Bindings

import scala.collection.immutable.{SortedMap, TreeSeqMap}

// an error that occurs during (de)serialization of JSON
final class WdlValueSerializationException(message: String) extends Exception(message)

object WdlValueSerde {
  def serialize(value: V, handler: Option[V => Option[JsValue]] = None): JsValue = {
    def inner(innerValue: V): JsValue = {
      val v = handler.flatMap(_(innerValue))
      if (v.isDefined) {
        return v.get
      }
      innerValue match {
        case V_Null | V_ForcedNull => JsNull
        case V_Boolean(value)      => JsBoolean(value)
        case V_Int(value)          => JsNumber(value)
        case V_Float(value)        => JsNumber(value)
        case V_String(value)       => JsString(value)
        case V_File(value)         => JsString(value)
        case V_Directory(value)    => JsString(value)

        // compound values
        case V_Optional(v) =>
          inner(v)
        case V_Array(vec) =>
          JsArray(vec.map(inner))
        case V_Pair(l, r) =>
          JsObject("left" -> inner(l), "right" -> inner(r))
        case V_Map(members) =>
          JsObject(
              members
                .map {
                  case (k, v) =>
                    val key = inner(k) match {
                      case JsString(value) => value
                      case other =>
                        throw new WdlValueSerializationException(
                            s"Cannot serialize non-string map key ${other}"
                        )
                    }
                    key -> inner(v)
                }
                .to(SortedMap)
          )
        case V_Object(members) =>
          JsObject(members.map { case (k, v) => k -> inner(v) }.to(SortedMap))
        case V_Struct(_, members) =>
          JsObject(members.map { case (k, v) => k -> inner(v) }.to(SortedMap))

        case other => throw new WdlValueSerializationException(s"value ${other} not supported")
      }
    }
    inner(value)
  }

  def serializeMap(wdlValues: Map[String, V],
                   handler: Option[V => Option[JsValue]] = None): Map[String, JsValue] = {
    wdlValues.view.mapValues(v => serialize(v, handler)).to(SortedMap)
  }

  def serializeBindings(bindings: Bindings[String, V],
                        handler: Option[V => Option[JsValue]] = None): Map[String, JsValue] = {
    serializeMap(bindings.toMap, handler)
  }

  def deserialize(jsValue: JsValue, handler: Option[JsValue => Option[V]] = None): V = {
    def inner(innerValue: JsValue): V = {
      val v = handler.flatMap(_(innerValue))
      if (v.isDefined) {
        return v.get
      }
      innerValue match {
        case JsNull                               => V_Null
        case JsFalse                              => V_Boolean(false)
        case JsTrue                               => V_Boolean(true)
        case JsNumber(value) if value.isValidLong => V_Int(value.toLongExact)
        case JsNumber(value)                      => V_Float(value.toDouble)
        case JsString(value)                      => V_String(value)
        case JsArray(items)                       => V_Array(items.map(inner))
        case JsObject(fields) =>
          V_Object(fields.map { case (k, v) => k -> inner(v) }.to(TreeSeqMap))
      }
    }
    inner(jsValue)
  }

  /**
    * Deserializes a JSON input value with a type to a `V`. Only handles supported types
    * with constant values. Input formats that support alternative representations (e.g. object
    * value for File) must pre-process those values first. Does *not* perform file localization.
    * @param name: the variable name (for error reporting)
    * @param wdlType: the destination type
    * @param jsValue: the JSON value
    * @return the WDL value
    */
  def deserializeWithType(
      jsValue: JsValue,
      wdlType: T,
      name: String = "",
      handler: Option[(JsValue, T) => Option[V]] = None
  ): V = {
    def inner(innerValue: JsValue, innerType: T, innerName: String): V = {
      val v = handler.flatMap(_(innerValue, innerType))
      if (v.isDefined) {
        return v.get
      }
      (innerType, innerValue) match {
        // primitive types
        case (T_Boolean, JsBoolean(b))  => V_Boolean(b.booleanValue)
        case (T_Int, JsNumber(i))       => V_Int(i.longValue)
        case (T_Float, JsNumber(f))     => V_Float(f.doubleValue)
        case (T_String, JsString(s))    => V_String(s)
        case (T_File, JsString(s))      => V_File(s)
        case (T_Directory, JsString(s)) => V_Directory(s)

        // maps
        case (T_Map(keyType, valueType), JsObject(fields)) =>
          val m = fields
            .map {
              case (k: String, v: JsValue) =>
                val kWdl = inner(JsString(k), keyType, s"${innerName}.${k}")
                val vWdl = inner(v, valueType, s"${innerName}.${k}")
                kWdl -> vWdl
            }
            .to(TreeSeqMap)
          V_Map(m)

        // two ways of writing a pair: an object, or an array
        // the WDL 1.0 spec contains a typo with field names as
        // "Left" and "Right", so we support both
        case (T_Pair(lType, rType), JsObject(fields))
            if fields.keySet.map(_.toLowerCase) == Set("left", "right") =>
          val fieldsLower = fields.map {
            case (name, value) => name.toLowerCase -> value
          }
          val left = inner(fieldsLower("left"), lType, s"${innerName}.left")
          val right = inner(fieldsLower("right"), rType, s"${innerName}.right")
          V_Pair(left, right)
        case (T_Pair(lType, rType), JsArray(Vector(l, r))) =>
          val left = inner(l, lType, s"${innerName}.left")
          val right = inner(r, rType, s"${innerName}.right")
          V_Pair(left, right)

        // empty array
        case (T_Array(_, _), JsNull) =>
          V_Array(Vector.empty)

        // array
        case (T_Array(t, _), JsArray(vec)) =>
          val wVec: Vector[V] = vec.zipWithIndex.map {
            case (elem: JsValue, index) =>
              inner(elem, t, s"${innerName}[${index}]")
          }
          V_Array(wVec)

        // optionals
        case (T_Optional(_), JsNull) =>
          V_Null
        case (T_Optional(t), jsv) =>
          val value = inner(jsv, t, innerName)
          V_Optional(value)

        // structs
        case (T_Struct(structName, typeMap), JsObject(fields)) =>
          // convert each field
          val m = typeMap
            .collect {
              case (name, t) if fields.contains(name) =>
                name -> inner(fields(name), t, s"${innerName}.${name}")
            }
          V_Struct(structName, m)

        case _ =>
          throw new WdlValueSerializationException(
              s"Unsupported value ${innerValue.prettyPrint} for input ${innerName} with type ${innerType}"
          )
      }
    }
    inner(jsValue, wdlType, name)
  }
}
