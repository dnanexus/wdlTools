package wdlTools.types

import dx.util.JsUtils
import spray.json._
import wdlTools.types.WdlTypes._

case class UnknownTypeException(message: String) extends Exception(message)

object WdlTypeSerde {

  def serializeType(t: WdlTypes.T): JsValue = {
    t match {
      case T_Boolean         => JsString("Boolean")
      case T_Int             => JsString("Int")
      case T_Float           => JsString("Float")
      case T_String          => JsString("String")
      case T_File            => JsString("File")
      case T_Directory       => JsString("Directory")
      case T_Object          => JsString("Object")
      case T_Struct(name, _) => JsString(name)
      case T_Array(memberType, nonEmpty) =>
        JsObject(
            Map(
                "name" -> JsString("Array"),
                "type" -> serializeType(memberType),
                "nonEmpty" -> JsBoolean(nonEmpty)
            )
        )
      case T_Pair(lType, rType) =>
        JsObject(
            Map(
                "name" -> JsString("Pair"),
                "leftType" -> serializeType(lType),
                "rightType" -> serializeType(rType)
            )
        )
      case T_Map(keyType, valueType) =>
        JsObject(
            Map(
                "name" -> JsString("Map"),
                "keyType" -> serializeType(keyType),
                "valueType" -> serializeType(valueType)
            )
        )
      case T_Optional(inner) =>
        serializeType(inner) match {
          case name: JsString =>
            JsObject(Map("name" -> name, "optional" -> JsBoolean(true)))
          case JsObject(fields) =>
            JsObject(fields + ("optional" -> JsBoolean(true)))
          case other =>
            throw new Exception(s"unhandled inner type ${other}")
        }
      case _ =>
        throw new Exception(s"Unhandled type ${t}")
    }
  }

  def simpleFromString(s: String): WdlTypes.T = {
    s match {
      case "Boolean"   => T_Boolean
      case "Int"       => T_Int
      case "Float"     => T_Float
      case "String"    => T_String
      case "File"      => T_File
      case "Directory" => T_Directory
      case "Object"    => T_Object
      case _ if s.endsWith("?") =>
        simpleFromString(s.dropRight(1)) match {
          case T_Optional(_) =>
            throw new Exception(s"nested optional type ${s}")
          case inner =>
            T_Optional(inner)
        }
      case s if s.contains("[") =>
        throw new Exception(s"type ${s} is not primitive")
      case _ =>
        throw UnknownTypeException(s"Unknown type ${s}")
    }
  }

  def deserializeType(jsValue: JsValue, typeAliases: Map[String, WdlTypes.T]): WdlTypes.T = {
    def resolveType(name: String): WdlTypes.T = {
      try {
        simpleFromString(name)
      } catch {
        case _: UnknownTypeException if typeAliases.contains(name) =>
          typeAliases(name)
      }
    }
    def inner(innerValue: JsValue): WdlTypes.T = {
      innerValue match {
        case JsString(name) => resolveType(name)
        case JsObject(fields) =>
          val t = fields("name") match {
            case JsString("Array") =>
              val arrayType = inner(fields("type"))
              val nonEmpty = fields.get("nonEmpty").exists(JsUtils.getBoolean(_))
              T_Array(arrayType, nonEmpty)
            case JsString("Map") =>
              val keyType = inner(fields("keyType"))
              val valueType = inner(fields("valueType"))
              T_Map(keyType, valueType)
            case JsString("Pair") =>
              val lType = inner(fields("leftType"))
              val rType = inner(fields("rightType"))
              T_Pair(lType, rType)
            case JsString(name) =>
              resolveType(name)
            case _ =>
              throw new Exception(s"unhandled type value ${innerValue}")
          }
          if (fields.get("optional").exists(JsUtils.getBoolean(_))) {
            T_Optional(t)
          } else {
            t
          }
        case other =>
          throw new Exception(s"unexpected type value ${other}")
      }
    }
    inner(jsValue)
  }
}
