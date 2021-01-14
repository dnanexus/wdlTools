package wdlTools.types

import dx.util.JsUtils
import spray.json._
import wdlTools.types.WdlTypes._

import scala.collection.immutable.TreeSeqMap

case class UnknownTypeException(message: String) extends Exception(message)

object WdlTypeSerde {

  def serializeStruct(structType: T_Struct,
                      typeAliases: Map[String, JsValue] = Map.empty,
                      withName: Boolean = true): (JsObject, Map[String, JsValue]) = {
    val (membersJs, newTypeAliases) =
      structType.members.foldLeft(Map.empty[String, JsValue], typeAliases) {
        case ((memberAccu, aliasAccu), (memberName, memberType)) =>
          val (memberTypeJs, newTypeAliases) = serializeType(memberType, aliasAccu)
          (memberAccu + (memberName -> memberTypeJs), newTypeAliases)
      }
    val jsValue = JsObject(
        "type" -> JsString("Struct"),
        "members" -> JsObject(membersJs)
    )
    (jsValue, newTypeAliases)
  }

  def serializeType(
      wdlType: WdlTypes.T,
      typeAliases: Map[String, JsValue] = Map.empty
  ): (JsValue, Map[String, JsValue]) = {
    val newTypeAliases = wdlType match {
      case structType: T_Struct if !typeAliases.contains(structType.name) =>
        val (structJs, newTypeAliases) = serializeStruct(structType, typeAliases, withName = false)
        newTypeAliases + (structType.name -> structJs)
      case _ => typeAliases
    }
    wdlType match {
      case p: T_Primitive =>
        val typeJs = p match {
          case T_Boolean   => JsString("Boolean")
          case T_Int       => JsString("Int")
          case T_Float     => JsString("Float")
          case T_String    => JsString("String")
          case T_File      => JsString("File")
          case T_Directory => JsString("Directory")
        }
        (typeJs, newTypeAliases)
      case T_Object          => (JsString("Object"), newTypeAliases)
      case T_Struct(name, _) => (JsString(name), newTypeAliases)
      case T_Array(memberType, nonEmpty) =>
        val (itemsJs, newTypeAliases2) = serializeType(memberType, newTypeAliases)
        (JsObject(
             "type" -> JsString("Array"),
             "items" -> itemsJs,
             "nonEmpty" -> JsBoolean(nonEmpty)
         ),
         newTypeAliases2)
      case T_Pair(leftType, rightType) =>
        val (leftJs, newTypeAliases2) = serializeType(leftType, newTypeAliases)
        val (rightJs, newTypeAliases3) = serializeType(rightType, newTypeAliases2)
        (JsObject(
             "type" -> JsString("Pair"),
             "left" -> leftJs,
             "right" -> rightJs
         ),
         newTypeAliases3)
      case T_Map(keyType, valueType) =>
        val (keysJs, newTypeAliases2) = serializeType(keyType, newTypeAliases)
        val (valuesJs, newTypeAliases3) = serializeType(valueType, newTypeAliases2)
        (JsObject(
             "type" -> JsString("Map"),
             "keys" -> keysJs,
             "values" -> valuesJs
         ),
         newTypeAliases3)
      case T_Optional(t) =>
        val (typeJs, newTypeAliases2) = serializeType(t, newTypeAliases)
        val optJs = typeJs match {
          case name: JsString =>
            JsObject(Map("type" -> name, "optional" -> JsBoolean(true)))
          case JsObject(fields) =>
            JsObject(fields + ("optional" -> JsBoolean(true)))
          case other =>
            throw new Exception(s"unhandled inner type ${other}")
        }
        (optJs, newTypeAliases2)
      case _ =>
        throw new Exception(s"Unhandled type ${wdlType}")
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
    def inner(innerValue: JsValue,
              innerAliases: Map[String, T],
              innerStructName: Option[String] = None): (WdlTypes.T, Map[String, WdlTypes.T]) = {
      innerValue match {
        case JsString(typeName) if innerAliases.contains(typeName) =>
          (innerAliases(typeName), innerAliases)
        case JsString(typeName) if jsSchemas.contains(typeName) =>
          inner(jsSchemas(typeName), innerAliases, Some(typeName))
        case JsString(typeName) =>
          (simpleFromString(typeName), innerAliases)
        case JsObject(fields) if fields.contains("type") =>
          val (wdlType, newAliases) = fields("type") match {
            case JsString("Array") =>
              val (arrayType, newAliases) = inner(fields("items"), innerAliases)
              val nonEmpty = fields.get("nonEmpty").exists(JsUtils.getBoolean(_))
              (T_Array(arrayType, nonEmpty), newAliases)
            case JsString("Map") =>
              val (keyType, newAliases1) = inner(fields("keys"), innerAliases)
              val (valueType, newAliases2) =
                inner(fields("values"), newAliases1)
              (T_Map(keyType, valueType), newAliases2)
            case JsString("Pair") =>
              val (lType, newAliases1) = inner(fields("left"), innerAliases)
              val (rType, newAliases2) = inner(fields("right"), newAliases1)
              (T_Pair(lType, rType), newAliases2)
            case JsString("Struct") =>
              val name = fields
                .get("name")
                .map {
                  case JsString(name) => name
                  case other          => throw new Exception(s"invalid struct name ${other}")
                }
                .orElse(innerStructName)
                .getOrElse(
                    throw new Exception(s"cannot determine name for struct ${fields}")
                )
              val (members, newAliases) = fields("members").asJsObject.fields
                .foldLeft(TreeSeqMap.empty[String, WdlTypes.T], innerAliases) {
                  case ((memberAccu, aliasAccu2), (memberName, memberValue)) =>
                    val (memberType, newAliases) =
                      inner(memberValue, aliasAccu2)
                    (memberAccu + (memberName -> memberType), newAliases)
                }
              (T_Struct(name, members), newAliases)
            case other =>
              inner(other, innerAliases)
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
    inner(jsValue, typeAliases, structName)
  }

  def deserializeTypes(
      jsValues: Map[String, JsValue],
      typeAliases: Map[String, WdlTypes.T] = Map.empty,
      jsSchemas: Map[String, JsValue] = Map.empty
  ): (Map[String, WdlTypes.T], Map[String, WdlTypes.T]) = {
    jsValues.foldLeft(TreeSeqMap.empty[String, WdlTypes.T], typeAliases) {
      case ((typeAccu, aliasAccu), (name, jsValue)) =>
        val (wdlType, newAliases) =
          deserializeType(jsValue, aliasAccu, jsSchemas)
        (typeAccu + (name -> wdlType), newAliases)
    }
  }
}
