package wdlTools.eval

import java.net.URL

import wdlTools.eval.WdlValues._
import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.TextSource
import wdlTools.typing.WdlTypes
import wdlTools.util.{EvalConfig, Options}

case class Eval(opts: Options,
                evalCfg: EvalConfig,
                structDefs : Map[String, WdlTypes.WT_Struct],
                docSourceURL: Option[URL]) {

  private def getStringVal(value: WV, text: TextSource): String = {
    value match {
      case WV_String(s) => s
      case WV_File(s)   => s
      case other        => throw new EvalException(s"bad value ${other}", text, docSourceURL)
    }
  }

  private def compareEqeq(a: WV, b: WV, text: TextSource): Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null)               => true
      case (WV_Boolean(b1), WV_Boolean(b2)) => b1 == b2
      case (WV_Int(i1), WV_Int(i2))         => i1 == i2
      case (WV_Float(x1), WV_Float(x2))     => x1 == x2
      case (WV_String(s1), WV_String(s2))   => s1 == s2
      case (WV_File(p1), WV_File(p2))       => p1 == p2

      case (WV_Pair(l1, r1), WV_Pair(l2, r2)) =>
        compareEqeq(l1, l2, text) && compareEqeq(r1, r2, text)

      // arrays
      case (WV_Array(a1), WV_Array(a2)) if a1.size != a2.size => false
      case (WV_Array(a1), WV_Array(a2))                       =>
        // All array elements must be equal
        (a1 zip a2).forall {
          case (x, y) => compareEqeq(x, y, text)
        }

      // maps
      case (WV_Map(m1), WV_Map(m2)) if m1.size != m2.size => false
      case (WV_Map(m1), WV_Map(m2)) =>
        val keysEqual = (m1.keys.toSet zip m2.keys.toSet).forall {
          case (k1, k2) => compareEqeq(k1, k2, text)
        }
        if (!keysEqual) {
          false
        } else {
          // now we know the keys are all equal
          m1.keys.forall {
            case k => compareEqeq(m1(k), m2(k), text)
          }
        }

      // optionals
      case (WV_Optional(v1), WV_Optional(v2)) =>
        compareEqeq(v1, v2, text)
      case (WV_Optional(v1), v2) =>
        compareEqeq(v1, v2, text)
      case (v1, WV_Optional(v2)) =>
        compareEqeq(v1, v2, text)

      // structs
      case (WV_Struct(name1, _), WV_Struct(name2, _)) if name1 != name2 => false
      case (WV_Struct(name, members1), WV_Struct(_, members2))
          if members1.keys.toSet != members2.keys.toSet =>
        // We need the type definition here. The other option is to assume it has already
        // been cleared at compile time.
        throw new Exception(s"error: struct ${name} does not have the corrent number of members")
      case (WV_Struct(_, members1), WV_Struct(_, members2)) =>
        members1.keys.forall {
          case k => compareEqeq(members1(k), members2(k), text)
        }

      case (_: WV_Object, _: WV_Object) =>
        throw new Exception("objects not implemented")
    }
  }

  private def compareLt(a: WV, b: WV, text: TextSource): Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null)             => false
      case (WV_Int(n1), WV_Int(n2))       => n1 < n2
      case (WV_Float(x1), WV_Int(n2))     => x1 < n2
      case (WV_Int(n1), WV_Float(x2))     => n1 < x2
      case (WV_Float(x1), WV_Float(x2))   => x1 < x2
      case (WV_String(s1), WV_String(s2)) => s1 < s2
      case (WV_File(p1), WV_File(p2))     => p1 < p2
      case (_, _) =>
        throw new EvalException("bad value should be a boolean", text, docSourceURL)
    }
  }

  private def compareLte(a: WV, b: WV, text: TextSource): Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null)             => true
      case (WV_Int(n1), WV_Int(n2))       => n1 <= n2
      case (WV_Float(x1), WV_Int(n2))     => x1 <= n2
      case (WV_Int(n1), WV_Float(x2))     => n1 <= x2
      case (WV_Float(x1), WV_Float(x2))   => x1 <= x2
      case (WV_String(s1), WV_String(s2)) => s1 <= s2
      case (WV_File(p1), WV_File(p2))     => p1 <= p2
      case (_, _) =>
        throw new EvalException("bad value should be a boolean", text, docSourceURL)
    }
  }

  private def compareGt(a: WV, b: WV, text: TextSource): Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null)             => false
      case (WV_Int(n1), WV_Int(n2))       => n1 > n2
      case (WV_Float(x), WV_Int(i))       => x > i
      case (WV_Int(i), WV_Float(x))       => i > x
      case (WV_Float(x1), WV_Float(x2))   => x1 > x2
      case (WV_String(s1), WV_String(s2)) => s1 > s2
      case (WV_File(p1), WV_File(p2))     => p1 > p2
      case (_, _) =>
        throw new EvalException("bad value should be a boolean", text, docSourceURL)
    }
  }

  private def compareGte(a: WV, b: WV, text: TextSource): Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null)             => true
      case (WV_Int(n1), WV_Int(n2))       => n1 >= n2
      case (WV_Float(x), WV_Int(i))       => x >= i
      case (WV_Int(i), WV_Float(x))       => i >= x
      case (WV_Float(x1), WV_Float(x2))   => x1 >= x2
      case (WV_String(s1), WV_String(s2)) => s1 >= s2
      case (WV_File(p1), WV_File(p2))     => p1 >= p2
      case (_, _) =>
        throw new EvalException("bad value should be a boolean", text, docSourceURL)
    }
  }

  private def add(a: WV, b: WV, text: TextSource): WV = {
    (a, b) match {
      case (WV_Int(n1), WV_Int(n2))     => WV_Int(n1 + n2)
      case (WV_Float(x1), WV_Int(n2))   => WV_Float(x1 + n2)
      case (WV_Int(n1), WV_Float(x2))   => WV_Float(n1 + x2)
      case (WV_Float(x1), WV_Float(x2)) => WV_Float(x1 + x2)

      // if we are adding strings, the result is a string
      case (WV_String(s1), WV_String(s2)) => WV_String(s1 + s2)
      case (WV_String(s1), WV_Int(n2))    => WV_String(s1 + n2.toString)
      case (WV_String(s1), WV_Float(x2))  => WV_String(s1 + x2.toString)
      case (WV_Int(n1), WV_String(s2))    => WV_String(n1.toString + s2)
      case (WV_Float(x1), WV_String(s2))  => WV_String(x1.toString + s2)
      case (WV_String(s), WV_Null)        => WV_String(s)

      // files
      case (WV_File(s1), WV_String(s2)) => WV_File(s1 + s2)
      case (WV_File(s1), WV_File(s2))   => WV_File(s1 + s2)

      case (_, _) =>
        throw new EvalException("cannot add these values", text, docSourceURL)
    }
  }

  private def sub(a: WV, b: WV, text: TextSource): WV = {
    (a, b) match {
      case (WV_Int(n1), WV_Int(n2))     => WV_Int(n1 - n2)
      case (WV_Float(x1), WV_Int(n2))   => WV_Float(x1 - n2)
      case (WV_Int(n1), WV_Float(x2))   => WV_Float(n1 - x2)
      case (WV_Float(x1), WV_Float(x2)) => WV_Float(x1 - x2)
      case (_, _) =>
        throw new EvalException(s"Expressions must be integers or floats", text, docSourceURL)
    }
  }

  private def mod(a: WV, b: WV, text: TextSource): WV = {
    (a, b) match {
      case (WV_Int(n1), WV_Int(n2))     => WV_Int(n1 % n2)
      case (WV_Float(x1), WV_Int(n2))   => WV_Float(x1 % n2)
      case (WV_Int(n1), WV_Float(x2))   => WV_Float(n1 % x2)
      case (WV_Float(x1), WV_Float(x2)) => WV_Float(x1 % x2)
      case (_, _) =>
        throw new EvalException(s"Expressions must be integers or floats", text, docSourceURL)
    }
  }

  private def multiply(a: WV, b: WV, text: TextSource): WV = {
    (a, b) match {
      case (WV_Int(n1), WV_Int(n2))     => WV_Int(n1 * n2)
      case (WV_Float(x1), WV_Int(n2))   => WV_Float(x1 * n2)
      case (WV_Int(n1), WV_Float(x2))   => WV_Float(n1 * x2)
      case (WV_Float(x1), WV_Float(x2)) => WV_Float(x1 * x2)
      case (_, _) =>
        throw new EvalException(s"Expressions must be integers or floats", text, docSourceURL)
    }
  }

  private def divide(a: WV, b: WV, text: TextSource): WV = {
    (a, b) match {
      case (WV_Int(n1), WV_Int(n2)) =>
        if (n2 == 0)
          throw new EvalException("DivisionByZero", text, docSourceURL)
        WV_Int(n1 / n2)
      case (WV_Float(x1), WV_Int(n2)) =>
        if (n2 == 0)
          throw new EvalException("DivisionByZero", text, docSourceURL)
        WV_Float(x1 / n2)
      case (WV_Int(n1), WV_Float(x2)) =>
        if (x2 == 0)
          throw new EvalException("DivisionByZero", text, docSourceURL)
        WV_Float(n1 / x2)
      case (WV_Float(x1), WV_Float(x2)) =>
        if (x2 == 0)
          throw new EvalException("DivisionByZero", text, docSourceURL)
        WV_Float(x1 / x2)
      case (_, _) =>
        throw new EvalException(s"Expressions must be integers or floats", text, docSourceURL)
    }
  }

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  private def exprGetName(value : WV, id : String, ctx : Context, text: TextSource) : WV = {
    value match {
      case WV_Struct(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Struct ${name} does not have member ${id}",
              text, docSourceURL)
          case Some(t) => t
        }

      case WV_Object(members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Object does not have member ${id}",
              text, docSourceURL)
          case Some(t) => t
        }

      case WV_Call(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Call object ${name} does not have member ${id}",
              text, docSourceURL)
          case Some(t) => t
        }

      // accessing a pair element
      case WV_Pair(l, _) if id.toLowerCase() == "left"  => l
      case WV_Pair(_, r) if id.toLowerCase() == "right" => r
      case WV_Pair(_, _) =>
        throw new EvalException(s"accessing a pair with (${id}) is illegal",
                                text, docSourceURL)

      case _ =>
        throw new EvalException(s"member access (${id}) in expression is illegal",
                                text, docSourceURL)
    }
  }

  def apply(expr: AST.Expr, ctx : Context): WdlValues.WV = {
    expr match {
      case AST.ValueNull(_) => WV_Null
      case AST.ValueBoolean(value, _) => WV_Boolean(value)
      case AST.ValueInt(value, _) => WV_Int(value)
      case AST.ValueFloat(value, _) => WV_Float(value)
      case AST.ValueString(value, _) => WV_String(value)
      case AST.ValueFile(value, _) => WV_File(value)

        // accessing a variable
      case AST.ExprIdentifier(id: String, _) if !(ctx.bindings contains id) =>
        throw new EvalException(s"accessing undefined variable ${id}, ctx=${ctx.bindings}")
      case AST.ExprIdentifier(id: String, _) =>
        ctx.bindings(id)

      // concatenate an array of strings inside a command block
      case AST.ExprCompoundString(vec: Vector[AST.Expr], _) =>
        val strArray: Vector[String] = vec.map { x =>
          val xv = apply(x, ctx)
          getStringVal(xv, x.text)
        }
        WV_String(strArray.mkString(""))

      case AST.ExprPair(l, r, _) => WV_Pair(apply(l, ctx), apply(r, ctx))
      case AST.ExprArray(array, _) =>
        WV_Array(array.map{ x => apply(x, ctx) })
      case AST.ExprMap(elements, _) =>
        WV_Map(elements.map{
                 case AST.ExprMapItem(k, v, _) => apply(k, ctx) -> apply(v, ctx)
               }.toMap)


      case AST.ExprObject(elements, _) =>
        WV_Object(elements.map{
                    case AST.ExprObjectMember(k, v, _) => k -> apply(v, ctx)
                  }.toMap)

      // ~{true="--yes" false="--no" boolean_value}
      case AST.ExprPlaceholderEqual(t, f, boolExpr, _) =>
        apply(boolExpr, ctx) match {
          case WV_Boolean(true)  => apply(t, ctx)
          case WV_Boolean(false) => apply(f, ctx)
          case other =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          expr.text,
                                          docSourceURL)
        }

      // ~{default="foo" optional_value}
      case AST.ExprPlaceholderDefault(defaultVal, optVal, _) =>
        apply(optVal, ctx) match {
          case WV_Null => apply(defaultVal, ctx)
          case other   => other
        }

      // ~{sep=", " array_value}
      case AST.ExprPlaceholderSep(sep: AST.Expr, arrayVal: AST.Expr, _) =>
        val sep2 = getStringVal(apply(sep, ctx), sep.text)
        apply(arrayVal, ctx) match {
          case WV_Array(ar) =>
            val elements: Vector[String] = ar.map { x =>
              getStringVal(x, expr.text)
            }
            WV_String(elements.mkString(sep2))
          case other =>
            throw new EvalException(s"bad value ${other}, should be a string",
                                          expr.text,
                                          docSourceURL)
        }

      // operators on one argument
      case AST.ExprUniraryPlus(e, _) =>
        apply(e, ctx) match {
          case WV_Float(f) => WV_Float(f)
          case WV_Int(k)   => WV_Int(k)
          case other =>
            throw new EvalException(s"bad value ${other}, should be a number",
                                          expr.text,
                                          docSourceURL)
        }

      case AST.ExprUniraryMinus(e, _) =>
        apply(e, ctx) match {
          case WV_Float(f) => WV_Float(-1 * f)
          case WV_Int(k)   => WV_Int(-1 * k)
          case other =>
            throw new EvalException(s"bad value ${other}, should be a number",
                                          expr.text,
                                          docSourceURL)
        }

      case AST.ExprNegate(e, _) =>
        apply(e, ctx) match {
          case WV_Boolean(b) => WV_Boolean(!b)
          case other =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          expr.text,
                                          docSourceURL)
        }

      // operators on two arguments
      case AST.ExprLor(a, b, _) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
                      (av, bv) match {
          case (WV_Boolean(a1), WV_Boolean(b1)) =>
            WV_Boolean(a1 || b1)
          case (WV_Boolean(_), other) =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          b.text,
                                          docSourceURL)
          case (other, _) =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          a.text,
                                          docSourceURL)
        }

      case AST.ExprLand(a, b, _) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        (av, bv) match {
          case (WV_Boolean(a1), WV_Boolean(b1)) =>
            WV_Boolean(a1 && b1)
          case (WV_Boolean(_), other) =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          b.text,
                                          docSourceURL)
          case (other, _) =>
            throw new EvalException(s"bad value ${other}, should be a boolean",
                                          a.text,
                                          docSourceURL)
        }

      // recursive comparison
      case AST.ExprEqeq(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(compareEqeq(av, bv, text))
      case AST.ExprNeq(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(!compareEqeq(av, bv, text))

      case AST.ExprLt(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(compareLt(av, bv, text))
      case AST.ExprLte(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(compareLte(av, bv, text))
      case AST.ExprGt(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(compareGt(av, bv, text))
      case AST.ExprGte(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        WV_Boolean(compareGte(av, bv, text))

      // Add is overloaded, can be used to add numbers or concatenate strings
      case AST.ExprAdd(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        add(av, bv, text)

      // Math operations
      case AST.ExprSub(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        sub(av, bv, text)
      case AST.ExprMod(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        mod(av, bv, text)
      case AST.ExprMul(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        multiply(av, bv, text)
      case AST.ExprDivide(a, b, text) =>
        val av = apply(a, ctx)
        val bv = apply(b, ctx)
        divide(av, bv, text)

      // Access an array element at [index]
      case AST.ExprAt(array, index, text) =>
        val array_v = apply(array, ctx)
        val index_v = apply(index, ctx)
        (array_v, index_v) match {
          case (WV_Array(av), WV_Int(n)) if n < av.size =>
            av(n)
          case (WV_Array(av), WV_Int(n)) =>
            val arraySize = av.size
            throw new EvalException(
                s"array access out of bounds (size=${arraySize}, element accessed=${n})",
                text,
                docSourceURL
            )
          case (_, _) =>
            throw new EvalException(s"array access requires an array and an integer",
                                          text,
                                          docSourceURL)
        }

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case AST.ExprIfThenElse(cond, tBranch, fBranch, text) =>
        val cond_v = apply(cond, ctx)
        cond_v match {
          case WV_Boolean(true)  => apply(tBranch, ctx)
          case WV_Boolean(false) => apply(fBranch, ctx)
          case other =>
            throw new EvalException(s"condition is not boolean", text, docSourceURL)
        }

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case AST.ExprApply(funcName, elements, text) =>
        throw new Exception("stdlib not implemented yet")

      // Access a field in a struct or an object. For example:
      //   Int z = x.a
      case AST.ExprGetName(e: AST.Expr, fieldName, text) =>
        val ev = apply(e, ctx)
        exprGetName(ev, fieldName, ctx, text)

      case other =>
        throw new Exception(s"expression ${AST.exprToString(other)} not implemented yet")
    }
  }

  private def coerceToStruct(structName : String,
                             memberDefs : Map[String, WdlTypes.WT],
                             members : Map[String, WV],
                             ctx : Context,
                             text : TextSource) : WV_Struct = {
    if (memberDefs.keys.toSet != members.keys.toSet)
      throw new EvalException(s"struct ${structName} has wrong fields", text, docSourceURL)

    // TODO: coerce the members to the right types
    WV_Struct(structName, members)
  }

  private def coerceTo(wdlType : WdlTypes.WT, value : WV, ctx : Context, text : TextSource) : WV = {
    (wdlType, value) match {
      // primitive types
      case (WdlTypes.WT_Boolean, WV_Boolean(_)) => value
      case (WdlTypes.WT_Int, WV_Int(_)) => value
      case (WdlTypes.WT_Int, WV_Float(x)) => WV_Int(x.toInt)
      case (WdlTypes.WT_Float, WV_Int(n)) => WV_Float(n.toFloat)
      case (WdlTypes.WT_Float, WV_Float(x)) => value
      case (WdlTypes.WT_String, WV_String(_))  => value
      case (WdlTypes.WT_String, WV_File(s))  => WV_String(s)
      case (WdlTypes.WT_File, WV_String(s)) => WV_File(s)
      case (WdlTypes.WT_File, WV_File(_)) => value

        // compound types
        // recursively descend into the sub structures and coerce them.
      case (WdlTypes.WT_Optional(t2), WV_Optional(value2)) =>
        WV_Optional(coerceTo(t2, value2, ctx, text))
      case (WdlTypes.WT_Array(t2), WV_Array(vec)) =>
//        if (nonEmpty && vec.isEmpty)
//          throw new EvalException("array is empty", text, docSourceURL)
        WV_Array(vec.map{ x => coerceTo(t2, x, ctx, text) })

      case (WdlTypes.WT_Map(kt, vt), WV_Map(m)) =>
        WV_Map(m.map{ case (k,v) =>
                 coerceTo(kt, k, ctx, text) -> coerceTo(vt, v, ctx, text)
               })
      case (WdlTypes.WT_Pair(lt, rt), WV_Pair(l,r)) =>
        WV_Pair(coerceTo(lt, l, ctx, text), coerceTo(rt, r, ctx, text))

      case (WdlTypes.WT_Struct(name1, _), WV_Struct(name2, _)) =>
        if (name1 != name2)
          throw new EvalException(s"cannot coerce struct ${name2} to struct ${name1}", text, docSourceURL)
        value

        // cast of an object to a struct. I think this is legal.
      case (WdlTypes.WT_Struct(name, memberDefs), WV_Object(members)) =>
        coerceToStruct(name, memberDefs, members, ctx, text)

      case (WdlTypes.WT_Struct(name, memberDefs), WV_Map(members)) =>
        // convert into a mapping from string to WdlValue
        val members2 : Map[String, WV] = members.map{
          case (WV_String(k),v) => k -> v
          case (other, _) =>
            throw new EvalException(s"${other} has to be a string for this to be a struct",
                                    text, docSourceURL)
        }
        coerceToStruct(name, memberDefs, members2, ctx, text)

      case (WdlTypes.WT_Object, WV_Object(_)) => value

      case (t, other) =>
        throw new EvalException(s"value ${other} cannot be coerced to type ${t}",
                                text, docSourceURL)
    }
  }


  private def typeFromAst(t : AST.Type, text : TextSource) : WdlTypes.WT = {
    def inner(t : AST.Type) : WdlTypes.WT = {
      t match {
        case AST.TypeBoolean(_) => WdlTypes.WT_Boolean
        case AST.TypeInt(_) => WdlTypes.WT_Int
        case AST.TypeFloat(_) => WdlTypes.WT_Float
        case AST.TypeString(_) => WdlTypes.WT_String
        case AST.TypeFile(_) => WdlTypes.WT_File

        case AST.TypeOptional(t, _) => WdlTypes.WT_Optional(inner(t))
        case AST.TypeArray(t, _, _) => WdlTypes.WT_Array(inner(t))
        case AST.TypeMap(k, v, _) => WdlTypes.WT_Map(inner(k), inner(v))
        case AST.TypePair(l, r, _) => WdlTypes.WT_Pair(inner(l), inner(r))

        // a variable whose type is a user defined struct
        case AST.TypeIdentifier(id, _) if structDefs contains id =>
          structDefs(id)
        case AST.TypeIdentifier(id, _) =>
          throw new EvalException(s"struct ${id} is undefined", text, docSourceURL)

        case AST.TypeObject(_) => WdlTypes.WT_Object

        case AST.TypeStruct(name, members, _, _) =>
          val members2 = members.map{
            case AST.StructMember(name, dataType, _, _) =>
              name -> inner(dataType)
          }.toMap
          WdlTypes.WT_Struct(name, members2)
      }
    }
    inner(t)
  }

  // Evaluate all the declarations and return a context
  def applyDeclarations(decls : Vector[AST.Declaration], ctx : Context) : Context = {
    decls.foldLeft(ctx) {
      case (accu, AST.Declaration(name, astWdlType, Some(expr), text, _)) =>
        val value = apply(expr, accu)
        val wdlType : WdlTypes.WT = typeFromAst(astWdlType, text)
        val value2 = coerceTo(wdlType, value, ctx, text)
        accu.addBinding(name, value2)
      case (accu, ast) =>
        throw new Exception(s"Can not evaluate element ${ast.getClass}")
    }
  }
}
