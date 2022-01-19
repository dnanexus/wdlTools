package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.SourceLocation
import wdlTools.types.WdlTypes

import scala.collection.immutable.{SeqMap, TreeSeqMap}
import scala.util.{Success, Try}

object Coercion {
  private def coerceToStruct(structName: String,
                             fieldTypes: SeqMap[String, WdlTypes.T],
                             fieldValues: SeqMap[String, V],
                             loc: SourceLocation,
                             allowNonstandardCoercions: Boolean,
                             isReadResult: Boolean): V_Struct = {
    if (fieldTypes.keys.toSet != fieldValues.keys.toSet) {
      throw new EvalException(s"struct ${structName} has wrong fields", loc)
    }

    // coerce each member to the struct type
    val coercedValues = fieldTypes.map {
      case (name, t) =>
        name -> coerceTo(t, fieldValues(name), loc, allowNonstandardCoercions, isReadResult)
    }

    V_Struct(structName, coercedValues)
  }

  def coerceTo(wdlType: WdlTypes.T,
               value: V,
               loc: SourceLocation = SourceLocation.empty,
               allowNonstandardCoercions: Boolean = false,
               isReadResult: Boolean = false): V = {
    def inner(innerType: WdlTypes.T, innerValue: V): V = {
      (innerType, innerValue) match {
        // basic coercion of primitive types
        case (WdlTypes.T_Optional(_), V_Null)    => V_Null
        case (WdlTypes.T_Boolean, b: V_Boolean)  => b
        case (WdlTypes.T_Int, i: V_Int)          => i
        case (WdlTypes.T_Float, f: V_Float)      => f
        case (WdlTypes.T_Float, V_Int(n))        => V_Float(n.toFloat)
        case (WdlTypes.T_String, s: V_String)    => s
        case (WdlTypes.T_File, f: V_File)        => f
        case (WdlTypes.T_File, V_String(s))      => V_File(s)
        case (WdlTypes.T_Directory, V_String(s)) => V_Directory(s)

        // primitives to string - I believe this is legal, but it may need to be non-standard
        case (WdlTypes.T_String, V_Boolean(b)) => V_String(b.toString)
        case (WdlTypes.T_String, V_Int(n))     => V_String(n.toString)
        case (WdlTypes.T_String, V_Float(x))   => V_String(x.toString)
        case (WdlTypes.T_String, V_File(s))    => V_String(s)

        // unwrap optional types/values
        case (WdlTypes.T_Optional(t), V_Optional(v)) =>
          V_Optional(inner(t, v))
        case (WdlTypes.T_Optional(t), v) =>
          V_Optional(inner(t, v))
        case (t, V_Optional(v)) =>
          inner(t, v)

        // compound types - recursively descend into the sub structures and coerce them.
        case (WdlTypes.T_Array(t, nonEmpty), V_Array(vec)) =>
          if (nonEmpty && vec.isEmpty)
            throw new EvalException("array is empty", loc)
          V_Array(vec.map { x =>
            inner(t, x)
          })
        case (WdlTypes.T_Map(kt, vt), V_Map(m)) =>
          V_Map(m.map {
            case (k, v) =>
              inner(kt, k) -> inner(vt, v)
          })
        case (WdlTypes.T_Pair(lt, rt), V_Pair(l, r)) =>
          V_Pair(inner(lt, l), inner(rt, r))
        case (WdlTypes.T_Object, obj: V_Object) =>
          obj
        case (WdlTypes.T_Struct(name1, members1), V_Struct(name2, members2)) =>
          if (name1 != name2) {
            throw new EvalException(s"cannot coerce struct ${name2} to struct ${name1}", loc)
          }
          // ensure 1) members2 keys are a subset of members1 keys, 2) members2
          // values are coercible to the corresponding types, and 3) any keys
          // in members1 that do not appear in members2 are optional
          val keys1 = members1.keys.toSet
          val keys2 = members2.keys.toSet
          val extra = keys2.diff(keys1)
          if (extra.nonEmpty) {
            throw new EvalException(
                s"""struct value has members that do not appear in the struct definition
                   |  name: ${name1}
                   |  type members: ${members1}
                   |  value members: ${members2}
                   |  extra members: ${extra}""".stripMargin,
                loc
            )
          }
          val missingNonOptional = keys1.diff(keys2).map(key => key -> members1(key)).filterNot {
            case (_, WdlTypes.T_Optional(_)) => false
            case _                           => true
          }
          if (missingNonOptional.nonEmpty) {
            throw new EvalException(
                s"""struct value is missing non-optional members
                   |  name: ${name1}
                   |  type members: ${members1}
                   |  value members: ${members2}
                   |  missing members: ${missingNonOptional}""".stripMargin,
                loc
            )
          }
          V_Struct(name1,
                   members2.map {
                     case (key, value) => key -> inner(members1(key), value)
                   })
        case (WdlTypes.T_Map(WdlTypes.T_String, valueType), V_Object(fields)) =>
          val mapFields: SeqMap[V, V] = fields.map {
            case (k, v) => V_String(k) -> inner(valueType, v)
          }
          V_Map(mapFields)
        case (WdlTypes.T_Struct(name, fieldTypes), V_Object(fields)) =>
          coerceToStruct(name, fieldTypes, fields, loc, allowNonstandardCoercions, isReadResult)
        case (WdlTypes.T_Struct(name, fieldTypes), V_Map(fields)) =>
          // this should probably be considered non-standard
          // convert into a mapping from string to WdlValue
          val fields2 = fields.map {
            case (V_String(k), v) => k -> v
            case (other, _) =>
              throw new EvalException(
                  s"Non-string map key ${other} cannot be coerced to struct member",
                  loc
              )
          }
          coerceToStruct(name, fieldTypes, fields2, loc, allowNonstandardCoercions, isReadResult)

        // non-standard coercions
        case (WdlTypes.T_String, V_Directory(s)) => V_String(s)
        case (WdlTypes.T_Boolean, V_String(s))
            if (allowNonstandardCoercions || isReadResult) && s == "true" =>
          V_Boolean(true)
        case (WdlTypes.T_Boolean, V_String(s))
            if (allowNonstandardCoercions || isReadResult) && s == "false" =>
          V_Boolean(false)
        case (WdlTypes.T_Int, V_String(s)) =>
          val n =
            try {
              s.toLong
            } catch {
              case _: NumberFormatException =>
                throw new EvalException(s"string ${s} cannot be converted into an Int", loc)
            }
          V_Int(n)
        case (WdlTypes.T_Float, V_String(s)) =>
          val x =
            try {
              s.toDouble
            } catch {
              case _: NumberFormatException =>
                throw new EvalException(s"string ${s} cannot be converted into a Float", loc)
            }
          V_Float(x)
        case (WdlTypes.T_Int, V_Float(f)) if allowNonstandardCoercions && f.isWhole =>
          V_Int(f.toLong)
        case (WdlTypes.T_Map(k, v), V_Array(array)) if allowNonstandardCoercions =>
          V_Map(
              array
                .map {
                  case V_Pair(l, r) => (inner(k, l), inner(v, r))
                  case _ => throw new EvalException(s"Cannot coerce array ${array} to Map", loc)
                }
                .to(TreeSeqMap)
          )
        case (WdlTypes.T_Array(WdlTypes.T_Pair(l, r), _), V_Map(map))
            if allowNonstandardCoercions =>
          V_Array(map.map {
            case (k, v) => V_Pair(inner(l, k), inner(r, v))
            case _      => throw new EvalException(s"Cannot coerce map ${map} to Array", loc)
          }.toVector)
        // TODO: Support Array[String] to Struct coercion as described in
        //  https://github.com/openwdl/wdl/issues/389
        case (WdlTypes.T_Any, any) => any
        case (t, other) =>
          throw new EvalException(s"value ${other} cannot be coerced to type ${t}", loc)
      }
    }
    inner(wdlType, value)
  }

  def coerceToFirst(wdlTypes: Vector[WdlTypes.T],
                    value: V,
                    loc: SourceLocation = SourceLocation.empty,
                    allowNonstandardCoercions: Boolean = false,
                    isReadResult: Boolean = false): WdlValues.V = {
    val coerced: WdlValues.V = wdlTypes
      .collectFirst { t =>
        Try(Coercion.coerceTo(t, value, loc, allowNonstandardCoercions, isReadResult)) match {
          case Success(v) => v
        }
      }
      .getOrElse(
          throw new EvalException(s"Value ${value} could not be coerced to one of ${wdlTypes}", loc)
      )
    coerced
  }
}
