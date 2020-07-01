package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.SourceLocation
import wdlTools.types.WdlTypes

object Coercion {
  private def coerceToStruct(structName: String,
                             memberDefs: Map[String, WdlTypes.T],
                             members: Map[String, V],
                             loc: SourceLocation): V_Struct = {
    if (memberDefs.keys.toSet != members.keys.toSet) {
      throw new EvalException(s"struct ${structName} has wrong fields", loc)
    }

    // coerce each member to the struct type
    val memValues: Map[String, V] = memberDefs.map {
      case (key, t) =>
        val memVal = coerceTo(t, members(key), loc)
        key -> memVal
    }

    V_Struct(structName, memValues)
  }

  def coerceTo(wdlType: WdlTypes.T, value: V, loc: SourceLocation): V = {
    (wdlType, value) match {
      // primitive types
      case (WdlTypes.T_Boolean, V_Boolean(_)) => value
      case (WdlTypes.T_Int, V_Int(_))         => value
      case (WdlTypes.T_Int, V_Float(x))       => V_Int(x.toInt)
      case (WdlTypes.T_Int, V_String(s)) =>
        val n =
          try {
            s.toInt
          } catch {
            case _: NumberFormatException =>
              throw new EvalException(s"string ${s} cannot be converted into an integer", loc)
          }
        V_Int(n)
      case (WdlTypes.T_Float, V_Int(n))   => V_Float(n.toFloat)
      case (WdlTypes.T_Float, V_Float(_)) => value
      case (WdlTypes.T_Float, V_String(s)) =>
        val x =
          try {
            s.toDouble
          } catch {
            case _: NumberFormatException =>
              throw new EvalException(s"string ${s} cannot be converted into an integer", loc)
          }
        V_Float(x)
      case (WdlTypes.T_String, V_Boolean(b)) => V_String(b.toString)
      case (WdlTypes.T_String, V_Int(n))     => V_String(n.toString)
      case (WdlTypes.T_String, V_Float(x))   => V_String(x.toString)
      case (WdlTypes.T_String, V_String(_))  => value
      case (WdlTypes.T_String, V_File(s))    => V_String(s)
      case (WdlTypes.T_File, V_String(s))    => V_File(s)
      case (WdlTypes.T_File, V_File(_))      => value

      // compound types
      // recursively descend into the sub structures and coerce them.
      case (WdlTypes.T_Optional(_), V_Null) => V_Null
      case (WdlTypes.T_Optional(t), V_Optional(v)) =>
        V_Optional(coerceTo(t, v, loc))
      case (WdlTypes.T_Optional(t), v) =>
        V_Optional(coerceTo(t, v, loc))
      case (t, V_Optional(v)) =>
        coerceTo(t, v, loc)

      case (WdlTypes.T_Array(t, nonEmpty), V_Array(vec)) =>
        if (nonEmpty && vec.isEmpty)
          throw new EvalException("array is empty", loc)
        V_Array(vec.map { x =>
          coerceTo(t, x, loc)
        })

      case (WdlTypes.T_Map(kt, vt), V_Map(m)) =>
        V_Map(m.map {
          case (k, v) =>
            coerceTo(kt, k, loc) -> coerceTo(vt, v, loc)
        })
      case (WdlTypes.T_Pair(lt, rt), V_Pair(l, r)) =>
        V_Pair(coerceTo(lt, l, loc), coerceTo(rt, r, loc))

      case (WdlTypes.T_Struct(name1, _), V_Struct(name2, _)) =>
        if (name1 != name2)
          throw new EvalException(s"cannot coerce struct ${name2} to struct ${name1}", loc)
        value

      // cast of an object to a struct. I think this is legal.
      case (WdlTypes.T_Struct(name, memberDefs), V_Object(members)) =>
        coerceToStruct(name, memberDefs, members, loc)

      case (WdlTypes.T_Struct(name, memberDefs), V_Map(members)) =>
        // convert into a mapping from string to WdlValue
        val members2: Map[String, V] = members.map {
          case (V_String(k), v) => k -> v
          case (other, _) =>
            throw new EvalException(s"${other} has to be a string for this to be a struct", loc)
        }
        coerceToStruct(name, memberDefs, members2, loc)

      case (WdlTypes.T_Object, V_Object(_)) => value

      case (t, other) =>
        throw new EvalException(s"value ${other} cannot be coerced to type ${t}", loc)
    }
  }
}
