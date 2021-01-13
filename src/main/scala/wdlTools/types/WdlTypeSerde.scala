package wdlTools.types

import dx.util.JsUtils
import spray.json._
import wdlTools.types.WdlTypes._

import scala.collection.immutable.TreeSeqMap

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
                "type" -> JsString("Array"),
                "items" -> serializeType(memberType),
                "nonEmpty" -> JsBoolean(nonEmpty)
            )
        )
      case T_Pair(lType, rType) =>
        JsObject(
            Map(
                "type" -> JsString("Pair"),
                "left" -> serializeType(lType),
                "right" -> serializeType(rType)
            )
        )
      case T_Map(keyType, valueType) =>
        JsObject(
            Map(
                "type" -> JsString("Map"),
                "keys" -> serializeType(keyType),
                "values" -> serializeType(valueType)
            )
        )
      case T_Optional(inner) =>
        serializeType(inner) match {
          case name: JsString =>
            JsObject(Map("type" -> name, "optional" -> JsBoolean(true)))
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

  def deserializeType(
      jsValue: JsValue,
      typeAliases: Map[String, T] = Map.empty,
      jsSchemas: Map[String, JsValue] = Map.empty,
      structName: Option[String] = None
  ): (WdlTypes.T, Map[String, WdlTypes.T]) = {
    jsValue match {
      case JsString(typeName) if typeAliases.contains(typeName) =>
        (typeAliases(typeName), typeAliases)
      case JsString(typeName) if jsSchemas.contains(typeName) =>
        deserializeType(jsSchemas(typeName), typeAliases, jsSchemas)
      case JsString(typeName) =>
        (simpleFromString(typeName), typeAliases)
      case JsObject(fields) if fields.contains("type") =>
        val (wdlType, newAliases) = fields("type") match {
          case JsString("Array") =>
            val (arrayType, newAliases) = deserializeType(fields("items"), typeAliases, jsSchemas)
            val nonEmpty = fields.get("nonEmpty").exists(JsUtils.getBoolean(_))
            (T_Array(arrayType, nonEmpty), newAliases)
          case JsString("Map") =>
            val (keyType, newAliases1) = deserializeType(fields("keys"), typeAliases, jsSchemas)
            val (valueType, newAliases2) =
              deserializeType(fields("values"), newAliases1, jsSchemas)
            (T_Map(keyType, valueType), newAliases2)
          case JsString("Pair") =>
            val (lType, newAliases1) = deserializeType(fields("left"), typeAliases, jsSchemas)
            val (rType, newAliases2) = deserializeType(fields("right"), newAliases1, jsSchemas)
            (T_Pair(lType, rType), newAliases2)
          case JsString("Struct") =>
            val name = fields
              .get("name")
              .map {
                case JsString(name) => name
                case other          => throw new Exception(s"invalid struct name ${other}")
              }
              .orElse(structName)
              .getOrElse(
                  throw new Exception(s"cannot determine name for struct ${fields}")
              )
            val (members, newAliases) = fields("members").asJsObject.fields
              .foldLeft(TreeSeqMap.empty[String, WdlTypes.T], typeAliases) {
                case ((memberAccu, aliasAccu2), (memberName, memberValue)) =>
                  val (memberType, newAliases) =
                    deserializeType(memberValue, aliasAccu2, jsSchemas)
                  (memberAccu + (memberName -> memberType), newAliases)
              }
            (T_Struct(name, members), newAliases)
          case other =>
            deserializeType(other, typeAliases, jsSchemas)
        }
        if (fields.get("optional").exists(JsUtils.getBoolean(_))) {
          (T_Optional(wdlType), newAliases)
        } else {
          (wdlType, newAliases)
        }
      case _ =>
        throw new Exception(s"unhandled type value ${jsValue}")
    }
  }

  def deserializeTypes(
      jsValues: Map[String, JsValue],
      typeAliases: Map[String, WdlTypes.T] = Map.empty,
      jsSchemas: Map[String, JsValue] = Map.empty
  ): (Map[String, WdlTypes.T], Map[String, WdlTypes.T]) = {
    jsValues.foldLeft(TreeSeqMap.empty[String, WdlTypes.T], typeAliases) {
      case ((typeAccu, aliasAccu), (name, jsValue)) =>
        val (wdlType, newAliases) = deserializeType(jsValue, aliasAccu, jsSchemas)
        (typeAccu + (name -> wdlType), newAliases)
    }
  }
}
