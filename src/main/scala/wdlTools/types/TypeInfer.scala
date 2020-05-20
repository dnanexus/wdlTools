package wdlTools.types

import java.nio.file.Paths

import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.TextSource
import wdlTools.syntax.{Util => SUtil}
import wdlTools.types.WdlTypes._
import wdlTools.types.Util.{isPrimitive, typeToString, exprToString}
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.util.TypeCheckingRegime._
import wdlTools.util.{Util => UUtil}

case class TypeInfer(conf: TypeOptions) {
  private val unify = Unification(conf)
  private val regime = conf.typeChecking
  // whether to throw an Exception when encountering a type error (true) or
  // to generate a T_Error type (false)
  private val errorAsException = conf.errorAsException

  // A group of bindings. This is typically a part of the context. For example,
  // the body of a scatter.
  type WdlType = WdlTypes.T
  type Bindings = Map[String, WdlType]
  object Bindings {
    val empty = Map.empty[String, WdlType]
  }

  // The add operation is overloaded.
  // 1) The result of adding two integers is an integer
  // 2)    -"-                   floats   -"-   float
  // 3)    -"-                   strings  -"-   string
  private def typeEvalAdd(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    def isPseudoString(x: WdlType): Boolean = {
      x match {
        case T_String             => true
        case T_Optional(T_String) => true
        case T_File               => true
        case T_Optional(T_File)   => true
        // Directory to String coercion is disallowed by the spec
        case T_Directory             => false
        case T_Optional(T_Directory) => false
        case _                       => false
      }
    }
    val t = (a.wdlType, b.wdlType) match {
      case (T_Int, T_Int)     => T_Int
      case (T_Float, T_Int)   => T_Float
      case (T_Int, T_Float)   => T_Float
      case (T_Float, T_Float) => T_Float

      // if we are adding strings, the result is a string
      case (T_String, T_String) => T_String
      case (T_String, T_Int)    => T_String
      case (T_String, T_Float)  => T_String
      case (T_Int, T_String)    => T_String
      case (T_Float, T_String)  => T_String

      // NON STANDARD
      // there are WDL programs where we add optional strings
      case (T_String, x) if isPseudoString(x)                   => T_String
      case (T_String, T_Optional(T_Int)) if regime == Lenient   => T_String
      case (T_String, T_Optional(T_Float)) if regime == Lenient => T_String

      // adding files/directories is equivalent to concatenating paths
      case (T_File, T_String | T_File)           => T_File
      case (T_Directory, T_String | T_Directory) => T_Directory

      case (_, _) =>
        val msg = s"Expressions ${exprToString(a)} and ${exprToString(b)} cannot be added"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
    t
  }

  // math operation on a single argument
  private def typeEvalMathOp(expr: TAT.Expr, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Int   => T_Int
      case T_Float => T_Float
      case _ =>
        val msg = s"${exprToString(expr)} must be an integer or a float"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  private def typeEvalMathOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Int, T_Int)     => T_Int
      case (T_Float, T_Int)   => T_Float
      case (T_Int, T_Float)   => T_Float
      case (T_Float, T_Float) => T_Float
      case (_, _) =>
        val msg =
          s"Expressions ${exprToString(a)} and ${exprToString(b)} must be integers or floats"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  private def typeEvalLogicalOp(expr: TAT.Expr, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Boolean => T_Boolean
      case other =>
        val msg = s"${exprToString(expr)} must be a boolean, it is ${typeToString(other)}"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  private def typeEvalLogicalOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Boolean, T_Boolean) => T_Boolean
      case (_, _) =>
        val msg = s"${exprToString(a)} and ${exprToString(b)} must have boolean type"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  private def typeEvalCompareOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    if (a.wdlType == b.wdlType) {
      // These could be complex types, such as Array[Array[Int]].
      return T_Boolean
    }

    // Even if the types are not the same, there are cases where they can
    // be compared.
    (a.wdlType, b.wdlType) match {
      case (T_Int, T_Float) => T_Boolean
      case (T_Float, T_Int) => T_Boolean
      case (_, _) =>
        val msg = s"Expressions ${exprToString(a)} and ${exprToString(b)} must have the same type"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  private def typeEvalExprGetName(expr: TAT.Expr, id: String, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Struct(name, members) =>
        members.get(id) match {
          case None =>
            val msg = s"Struct ${name} does not have member ${id} in expression"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case Some(t) =>
            t
        }
      case T_Call(name, members) =>
        members.get(id) match {
          case None =>
            val msg = s"Call object ${name} does not have member ${id} in expression"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case Some(t) =>
            t
        }
      // An identifier is a struct, and we want to access
      // a field in it.
      // Person p = census.p
      // String name = p.name
      case T_Identifier(structName) =>
        // produce the struct definition
        ctx.structs.get(structName) match {
          case None =>
            val msg = s"unknown struct ${structName}"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case Some(T_Struct(_, members)) =>
            members.get(id) match {
              case None =>
                val msg = s"Struct ${structName} does not have member ${id}"
                if (errorAsException) {
                  throw new TypeException(msg, expr.text, ctx.docSourceUrl)
                } else {
                  T_Error(msg)
                }
              case Some(t) => t
            }
          case other =>
            val msg = s"not a struct ${other}"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
        }

      // accessing a pair element
      case T_Pair(l, _) if id.toLowerCase() == "left"  => l
      case T_Pair(_, r) if id.toLowerCase() == "right" => r
      case T_Pair(_, _) =>
        val msg = s"accessing a pair with (${id}) is illegal"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }

      case _ =>
        val msg = s"member access (${id}) in expression is illegal"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  // unify a vector of types
  private def unifyTypes(vec: Iterable[WdlType],
                         errMsg: String,
                         textSource: TextSource,
                         ctx: Context): WdlType = {
    try {
      val (t, _) = unify.unifyCollection(vec, Map.empty)
      t
    } catch {
      case _: TypeUnificationException =>
        val msg = errMsg + " must have the same type, or be coercible to one"
        if (errorAsException) {
          throw new TypeException(msg, textSource, ctx.docSourceUrl)
        } else {
          T_Error(msg)
        }
    }
  }

  // Add the type to an expression
  //
  private def applyExpr(expr: AST.Expr, bindings: Bindings, ctx: Context): TAT.Expr = {
    expr match {
      // null can be any type optional
      case AST.ValueNull(text)           => TAT.ValueNull(T_Optional(T_Any), text)
      case AST.ValueBoolean(value, text) => TAT.ValueBoolean(value, T_Boolean, text)
      case AST.ValueInt(value, text)     => TAT.ValueInt(value, T_Int, text)
      case AST.ValueFloat(value, text)   => TAT.ValueFloat(value, T_Float, text)
      case AST.ValueString(value, text)  => TAT.ValueString(value, T_String, text)

      // an identifier has to be bound to a known type. Lookup the the type,
      // and add it to the expression.
      case AST.ExprIdentifier(id, text) =>
        val t = ctx.lookup(id, bindings, text) match {
          case None =>
            val msg = s"Identifier ${id} is not defined"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case Some(t) =>
            t
        }
        TAT.ExprIdentifier(id, t, text)

      // All the sub-exressions have to be strings, or coercible to strings
      case AST.ExprCompoundString(vec, text) =>
        val initialType: T = T_String
        val (vec2, wdlType) = vec.foldLeft((Vector.empty, initialType)) {
          case ((v, t), subExpr) =>
            val e2 = applyExpr(subExpr, bindings, ctx)
            val t2: T = if (unify.isCoercibleTo(T_String, e2.wdlType)) {
              t
            } else {
              val msg =
                s"expression ${exprToString(e2)} of type ${e2.wdlType} is not coercible to string"
              if (errorAsException) {
                throw new TypeException(msg, expr.text, ctx.docSourceUrl)
              } else if (t == initialType) {
                T_Error(msg)
              } else {
                t
              }
            }
            (v :+ e2, t2)
        }
        TAT.ExprCompoundString(vec2, wdlType, text)

      case AST.ExprPair(l, r, text) =>
        val l2 = applyExpr(l, bindings, ctx)
        val r2 = applyExpr(r, bindings, ctx)
        val t = T_Pair(l2.wdlType, r2.wdlType)
        TAT.ExprPair(l2, r2, t, text)

      case AST.ExprArray(vec, text) if vec.isEmpty =>
        // The array is empty, we can't tell what the array type is.
        TAT.ExprArray(Vector.empty, T_Array(T_Any), text)

      case AST.ExprArray(vec, text) =>
        val tVecExprs = vec.map(applyExpr(_, bindings, ctx))
        val t =
          try {
            T_Array(unify.unifyCollection(tVecExprs.map(_.wdlType), Map.empty)._1)
          } catch {
            case _: TypeUnificationException =>
              val msg = "array elements must have the same type, or be coercible to one"
              if (errorAsException) {
                throw new TypeException(msg, expr.text, ctx.docSourceUrl)
              } else {
                T_Error(msg)
              }
          }
        TAT.ExprArray(tVecExprs, t, text)

      case AST.ExprObject(members, text) =>
        val members2 = members.map {
          case AST.ExprObjectMember(key, value, _) =>
            key -> applyExpr(value, bindings, ctx)
        }.toMap
        TAT.ExprObject(members2, T_Object, text)

      case AST.ExprMap(m, text) if m.isEmpty =>
        // The key and value types are unknown.
        TAT.ExprMap(Map.empty, T_Map(T_Any, T_Any), text)

      case AST.ExprMap(value, text) =>
        // figure out the types from the first element
        val m: Map[TAT.Expr, TAT.Expr] = value.map { item: AST.ExprMapItem =>
          applyExpr(item.key, bindings, ctx) -> applyExpr(item.value, bindings, ctx)
        }.toMap
        // unify the key types
        val tk = unifyTypes(m.keys.map(_.wdlType), "map keys", text, ctx)
        // unify the value types
        val tv = unifyTypes(m.values.map(_.wdlType), "map values", text, ctx)
        TAT.ExprMap(m, T_Map(tk, tv), text)

      // These are expressions like:
      // ${true="--yes" false="--no" boolean_value}
      case AST.ExprPlaceholderEqual(t: AST.Expr, f: AST.Expr, value: AST.Expr, text) =>
        val te = applyExpr(t, bindings, ctx)
        val fe = applyExpr(f, bindings, ctx)
        val ve = applyExpr(value, bindings, ctx)
        val wdlType = if (te.wdlType != fe.wdlType) {
          val msg =
            s"subexpressions ${exprToString(te)} and ${exprToString(fe)} must have the same type"
          if (errorAsException) {
            throw new TypeException(msg, text, ctx.docSourceUrl)
          } else {
            T_Error(msg)
          }
        } else if (ve.wdlType != T_Boolean) {
          val msg =
            s"condition ${exprToString(ve)} should have boolean type, it has type ${typeToString(ve.wdlType)} instead"
          if (errorAsException) {
            throw new TypeException(msg, expr.text, ctx.docSourceUrl)
          } else {
            T_Error(msg)
          }
        } else {
          te.wdlType
        }
        TAT.ExprPlaceholderEqual(te, fe, ve, wdlType, text)

      // An expression like:
      // ${default="foo" optional_value}
      case AST.ExprPlaceholderDefault(default: AST.Expr, value: AST.Expr, text) =>
        val de = applyExpr(default, bindings, ctx)
        val ve = applyExpr(value, bindings, ctx)
        val t = ve.wdlType match {
          case T_Optional(vt2) if unify.isCoercibleTo(de.wdlType, vt2) => de.wdlType
          case vt2 if unify.isCoercibleTo(de.wdlType, vt2)             =>
            // another unsavory case. The optional_value is NOT optional.
            de.wdlType
          case _ =>
            val msg = s"""|Expression (${exprToString(ve)}) must have type coercible to
                          |(${typeToString(de.wdlType)}), it has type (${typeToString(ve.wdlType)}) instead
                          |""".stripMargin.replaceAll("\n", " ")
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
        }
        TAT.ExprPlaceholderDefault(de, ve, t, text)

      // An expression like:
      // ${sep=", " array_value}
      case AST.ExprPlaceholderSep(sep: AST.Expr, value: AST.Expr, text) =>
        val se = applyExpr(sep, bindings, ctx)
        if (se.wdlType != T_String)
          throw new TypeException(s"separator ${exprToString(se)} must have string type",
                                  text,
                                  ctx.docSourceUrl)
        val ve = applyExpr(value, bindings, ctx)
        val t = ve.wdlType match {
          case T_Array(x, _) if unify.isCoercibleTo(T_String, x) =>
            T_String
          case other =>
            val msg =
              s"expression ${exprToString(ve)} should be coercible to Array[String], but it is ${typeToString(other)}"
            if (errorAsException) {
              throw new TypeException(msg, ve.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
        }
        TAT.ExprPlaceholderSep(se, ve, t, text)

      // math operators on one argument
      case AST.ExprUniraryPlus(value, text) =>
        val ve = applyExpr(value, bindings, ctx)
        TAT.ExprUniraryPlus(ve, typeEvalMathOp(ve, ctx), text)
      case AST.ExprUniraryMinus(value, text) =>
        val ve = applyExpr(value, bindings, ctx)
        TAT.ExprUniraryMinus(ve, typeEvalMathOp(ve, ctx), text)

      // logical operators
      case AST.ExprLor(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprLor(ae, be, typeEvalLogicalOp(ae, be, ctx), text)
      case AST.ExprLand(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprLand(ae, be, typeEvalLogicalOp(ae, be, ctx), text)
      case AST.ExprNegate(value: AST.Expr, text) =>
        val e = applyExpr(value, bindings, ctx)
        TAT.ExprNegate(e, typeEvalLogicalOp(e, ctx), text)

      // equality comparisons
      case AST.ExprEqeq(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprEqeq(ae, be, typeEvalCompareOp(ae, be, ctx), text)
      case AST.ExprNeq(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprNeq(ae, be, typeEvalCompareOp(ae, be, ctx), text)
      case AST.ExprLt(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprLt(ae, be, typeEvalCompareOp(ae, be, ctx), text)
      case AST.ExprGte(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprGte(ae, be, typeEvalCompareOp(ae, be, ctx), text)
      case AST.ExprLte(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprLte(ae, be, typeEvalCompareOp(ae, be, ctx), text)
      case AST.ExprGt(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprGt(ae, be, typeEvalCompareOp(ae, be, ctx), text)

      // add is overloaded, it is a special case
      case AST.ExprAdd(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprAdd(ae, be, typeEvalAdd(ae, be, ctx), text)

      // math operators on two arguments
      case AST.ExprSub(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprSub(ae, be, typeEvalMathOp(ae, be, ctx), text)
      case AST.ExprMod(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprMod(ae, be, typeEvalMathOp(ae, be, ctx), text)
      case AST.ExprMul(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprMul(ae, be, typeEvalMathOp(ae, be, ctx), text)
      case AST.ExprDivide(a: AST.Expr, b: AST.Expr, text) =>
        val ae = applyExpr(a, bindings, ctx)
        val be = applyExpr(b, bindings, ctx)
        TAT.ExprDivide(ae, be, typeEvalMathOp(ae, be, ctx), text)

      // Access an array element at [index]
      case AST.ExprAt(array: AST.Expr, index: AST.Expr, text) =>
        val eIndex = applyExpr(index, bindings, ctx)
        val eArray = applyExpr(array, bindings, ctx)
        val t = (eIndex.wdlType, eArray.wdlType) match {
          case (T_Int, T_Array(elementType, _)) => elementType
          case (T_Int, _) =>
            val msg = s"${exprToString(eIndex)} must be an integer"
            if (errorAsException) {
              throw new TypeException(msg, text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case (_, _) =>
            val msg = s"expression ${exprToString(eArray)} must be an array"
            if (errorAsException) {
              throw new TypeException(msg, eArray.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
        }
        TAT.ExprAt(eArray, eIndex, t, text)

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case AST.ExprIfThenElse(cond: AST.Expr, trueBranch: AST.Expr, falseBranch: AST.Expr, text) =>
        val eCond = applyExpr(cond, bindings, ctx)
        val eTrueBranch = applyExpr(trueBranch, bindings, ctx)
        val eFalseBranch = applyExpr(falseBranch, bindings, ctx)
        val t = {
          if (eCond.wdlType != T_Boolean) {
            val msg = s"condition ${exprToString(eCond)} must be a boolean"
            if (errorAsException) {
              throw new TypeException(msg, eCond.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          } else {
            try {
              unify.unify(eTrueBranch.wdlType, eFalseBranch.wdlType, Map.empty)._1
            } catch {
              case _: TypeUnificationException =>
                val msg =
                  s"""|The branches of a conditional expression must be coercable to the same type
                      |expression
                      |  true branch: ${typeToString(eTrueBranch.wdlType)}
                      |  flase branch: ${typeToString(eFalseBranch.wdlType)}
                      |""".stripMargin
                if (errorAsException) {
                  throw new TypeException(msg, expr.text, ctx.docSourceUrl)
                } else {
                  T_Error(msg)
                }
            }
          }
        }
        TAT.ExprIfThenElse(eCond, eTrueBranch, eFalseBranch, t, text)

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case AST.ExprApply(funcName: String, elements: Vector[AST.Expr], text) =>
        val eElements = elements.map(applyExpr(_, bindings, ctx))
        val (outputType, funcSig) = ctx.stdlib.apply(funcName, eElements.map(_.wdlType), expr.text)
        TAT.ExprApply(funcName, funcSig, eElements, outputType, text)

      // Access a field in a struct or an object. For example "x.a" in:
      //   Int z = x.a
      case AST.ExprGetName(expr: AST.Expr, id: String, text) =>
        val e = applyExpr(expr, bindings, ctx)
        val t = typeEvalExprGetName(e, id, ctx)
        TAT.ExprGetName(e, id, t, text)
    }
  }

  private def typeFromAst(t: AST.Type, text: TextSource, ctx: Context): WdlType = {
    t match {
      case _: AST.TypeBoolean     => T_Boolean
      case _: AST.TypeInt         => T_Int
      case _: AST.TypeFloat       => T_Float
      case _: AST.TypeString      => T_String
      case _: AST.TypeFile        => T_File
      case _: AST.TypeDirectory   => T_Directory
      case AST.TypeOptional(t, _) => T_Optional(typeFromAst(t, text, ctx))
      case AST.TypeArray(t, _, _) => T_Array(typeFromAst(t, text, ctx))
      case AST.TypeMap(k, v, _)   => T_Map(typeFromAst(k, text, ctx), typeFromAst(v, text, ctx))
      case AST.TypePair(l, r, _)  => T_Pair(typeFromAst(l, text, ctx), typeFromAst(r, text, ctx))
      case AST.TypeIdentifier(id, _) =>
        ctx.structs.get(id) match {
          case None =>
            val msg = s"struct ${id} has not been defined"
            if (errorAsException) {
              throw new TypeException(msg, text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          case Some(struct) => struct
        }
      case _: AST.TypeObject => T_Object
      case AST.TypeStruct(name, members, _) =>
        T_Struct(name, members.map {
          case AST.StructMember(name, t2, _) => name -> typeFromAst(t2, text, ctx)
        }.toMap)
    }
  }

  // check the declaration and add a binding for its (variable -> wdlType)
  //
  // In a declaration the right hand type must be coercible to
  // the left hand type.
  //
  // Examples:
  //   Int x
  //   Int x = 5
  //   Int x = 7 + y
  private def applyDecl(decl: AST.Declaration,
                        bindings: Map[String, WdlType],
                        ctx: Context,
                        canShadow: Boolean = false): TAT.Declaration = {
    // There are cases where we want to allow shadowing. For example, it
    // is legal to have an output variable with the same name as an input variable.
    if (!canShadow && ctx.lookup(decl.name, bindings, decl.text).isEmpty) {
      val msg = s"variable ${decl.name} shadows an existing variable"
      val wdlType = if (errorAsException) {
        throw new TypeException(msg, decl.text, ctx.docSourceUrl)
      } else {
        T_Error(msg)
      }
      TAT.Declaration(decl.name, wdlType, None, decl.text)
    } else {
      val lhsType = typeFromAst(decl.wdlType, decl.text, ctx)
      decl.expr match {
        // Int x
        case None =>
          TAT.Declaration(decl.name, lhsType, None, decl.text)
        case Some(expr) =>
          val e = applyExpr(expr, bindings, ctx)
          val rhsType = e.wdlType
          val wdlType = if (unify.isCoercibleTo(lhsType, rhsType)) {
            lhsType
          } else {
            val msg =
              s"""|${decl.name} is of type ${typeToString(lhsType)}
                  |but is assigned ${typeToString(rhsType)}
                  |${exprToString(e)}
                  |""".stripMargin.replaceAll("\n", " ")
            if (errorAsException) {
              throw new TypeException(msg, decl.text, ctx.docSourceUrl)
            } else {
              T_Error(msg)
            }
          }
          TAT.Declaration(decl.name, wdlType, Some(e), decl.text)
      }
    }
  }

  // type check the input section, and see that there are no double definitions.
  // return a new typed input section
  //
  private def applyInputSection(inputSection: AST.InputSection, ctx: Context): TAT.InputSection = {
    // translate each declaration
    val (tDecls, _, _) =
      inputSection.declarations
        .foldLeft((Vector.empty[TAT.Declaration], Set.empty[String], Bindings.empty)) {
          case ((tDecls, names, bindings), decl) =>
            val tDecl = applyDecl(decl, bindings, ctx) match {
              case d if names.contains(d.name) =>
                val msg = s"Input section has duplicate definition ${d.name}"
                if (errorAsException) {
                  throw new TypeException(msg, inputSection.text, ctx.docSourceUrl)
                } else {
                  TAT.Declaration(d.name, T_Error(msg), d.expr, d.text)
                }
              case d => d
            }
            (tDecls :+ tDecl, names + decl.name, bindings + (tDecl.name -> tDecl.wdlType))
        }
    TAT.InputSection(tDecls, inputSection.text)
  }

  // Calculate types for the outputs, and return a new typed output section
  private def applyOutputSection(outputSection: AST.OutputSection,
                                 ctx: Context): TAT.OutputSection = {
    // output variables can shadow input definitions, but not intermediate
    // values. This is weird, but is used here:
    // https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/tasks/JointGenotypingTasks-terra.wdl#L590
    val both = outputSection.declarations.map(_.name).toSet intersect ctx.declarations.keys.toSet

    // translate the declarations
    val (tDecls, _, _) =
      outputSection.declarations
        .foldLeft((Vector.empty[TAT.Declaration], Set.empty[String], Bindings.empty)) {
          case ((tDecls, names, bindings), decl) =>
            // check the declaration and add a binding for its (variable -> wdlType)
            val tDecl = applyDecl(decl, bindings, ctx, canShadow = true) match {
              case d if names.contains(d.name) =>
                val msg = s"Output section has duplicate definition ${d.name}"
                if (errorAsException) {
                  throw new TypeException(msg, d.text, ctx.docSourceUrl)
                } else {
                  TAT.Declaration(d.name, T_Error(msg), d.expr, d.text)
                }
              case d if both.contains(d.name) =>
                val msg = s"Definition ${d.name} shadows exisiting declarations"
                if (errorAsException) {
                  throw new TypeException(msg, d.text, ctx.docSourceUrl)
                } else {
                  TAT.Declaration(d.name, T_Error(msg), d.expr, d.text)
                }
              case d => d
            }
            val bindings2 = bindings + (tDecl.name -> tDecl.wdlType)
            (tDecls :+ tDecl, names + tDecl.name, bindings2)
        }

    TAT.OutputSection(tDecls, outputSection.text)
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(
      inputSection: Option[TAT.InputSection],
      outputSection: Option[TAT.OutputSection]
  ): (Map[String, (WdlType, Boolean)], Map[String, WdlType]) = {

    val inputType: Map[String, (WdlType, Boolean)] = inputSection match {
      case None => Map.empty
      case Some(TAT.InputSection(decls, _)) =>
        decls.map {
          case TAT.Declaration(name, wdlType, Some(_), _) =>
            // input has a default value, caller may omit it.
            name -> (wdlType, true)

          case TAT.Declaration(name, T_Optional(t), None, _) =>
            // input is optional, caller can omit it.
            name -> (T_Optional(t), true)

          case TAT.Declaration(name, t, None, _) =>
            // input is compulsory
            name -> (t, false)
        }.toMap
    }
    val outputType: Map[String, WdlType] = outputSection match {
      case None => Map.empty
      case Some(TAT.OutputSection(decls, _)) =>
        decls.map { tDecl =>
          tDecl.name -> tDecl.wdlType
        }.toMap
    }
    (inputType, outputType)
  }

  // The runtime section can make use of values defined in declarations
  private def applyRuntime(rtSection: AST.RuntimeSection, ctx: Context): TAT.RuntimeSection = {
    val m = rtSection.kvs.map {
      case AST.RuntimeKV(name, expr, _) =>
        name -> applyExpr(expr, Map.empty, ctx)
    }.toMap
    TAT.RuntimeSection(m, rtSection.text)
  }

  private def applyHints(hintsSection: AST.HintsSection, ctx: Context): TAT.HintsSection = {
    val m = hintsSection.kvs.map {
      case AST.HintsKV(name, expr, _) =>
        name -> applyExpr(expr, Map.empty, ctx)
    }.toMap
    TAT.HintsSection(m, hintsSection.text)
  }

  // convert the generic expression syntax into a specialized JSON object
  // language for meta values only.
  private def metaValueFromExpr(expr: AST.Expr, ctx: Context): TAT.MetaValue = {
    expr match {
      case AST.ValueNull(_)                          => TAT.MetaNull
      case AST.ValueBoolean(value, _)                => TAT.MetaBoolean(value)
      case AST.ValueInt(value, _)                    => TAT.MetaInt(value)
      case AST.ValueFloat(value, _)                  => TAT.MetaFloat(value)
      case AST.ValueString(value, _)                 => TAT.MetaString(value)
      case AST.ExprIdentifier(id, _) if id == "null" => TAT.MetaNull
      case AST.ExprIdentifier(id, _)                 => TAT.MetaString(id)
      case AST.ExprArray(vec, _)                     => TAT.MetaArray(vec.map(metaValueFromExpr(_, ctx)))
      case AST.ExprMap(members, _) =>
        TAT.MetaObject(members.map {
          case AST.ExprMapItem(AST.ValueString(key, _), value, _) =>
            key -> metaValueFromExpr(value, ctx)
          case AST.ExprMapItem(AST.ExprIdentifier(key, _), value, _) =>
            key -> metaValueFromExpr(value, ctx)
          case other =>
            throw new RuntimeException(s"bad value ${SUtil.exprToString(other)}")
        }.toMap)
      case AST.ExprObject(members, _) =>
        TAT.MetaObject(members.map {
          case AST.ExprObjectMember(key, value, _) =>
            key -> metaValueFromExpr(value, ctx)
          case other =>
            throw new RuntimeException(s"bad value ${SUtil.exprToString(other)}")
        }.toMap)
      case other =>
        val msg = s"${SUtil.exprToString(other)} is an invalid meta value"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          TAT.MetaError(msg)
        }
    }
  }

  private def applyMeta(metaSection: AST.MetaSection, ctx: Context): TAT.MetaSection = {
    TAT.MetaSection(metaSection.kvs.map {
      case AST.MetaKV(k, v, _) =>
        k -> metaValueFromExpr(v, ctx)
    }.toMap, metaSection.text)
  }

  private def applyParamMeta(paramMetaSection: AST.ParameterMetaSection,
                             ctx: Context): TAT.ParameterMetaSection = {
    TAT.ParameterMetaSection(
        paramMetaSection.kvs.map { kv: AST.MetaKV =>
          val metaValue = if (ctx.inputs.contains(kv.id) || ctx.outputs.contains(kv.id)) {
            metaValueFromExpr(kv.expr, ctx)
          } else {
            val msg =
              s"parameter_meta key ${kv.id} does not refer to an input or output declaration"
            if (errorAsException) {
              throw new TypeException(msg, kv.text, ctx.docSourceUrl)
            } else {
              TAT.MetaError(msg)
            }
          }
          kv.id -> metaValue
        }.toMap,
        paramMetaSection.text
    )
  }

  // TASK
  //
  // - An inputs type has to match the type of its default value (if any)
  // - Check the declarations
  // - Assignments to an output variable must match
  //
  // We can't check the validity of the command section.
  private def applyTask(task: AST.Task, ctxOuter: Context): TAT.Task = {
    val (tInputSection, ctx) = task.input match {
      case None =>
        (None, ctxOuter)
      case Some(inpSection) =>
        val tInpSection = applyInputSection(inpSection, ctxOuter)
        val ctx = ctxOuter.bindInputSection(tInpSection)
        (Some(tInpSection), ctx)
    }

    // add types to the declarations, and accumulate context
    val (tDeclarations, bindings) =
      task.declarations.foldLeft((Vector.empty[TAT.Declaration], Bindings.empty)) {
        case ((tDecls, bindings), decl) =>
          val tDecl = applyDecl(decl, bindings, ctx)
          (tDecls :+ tDecl, bindings + (tDecl.name -> tDecl.wdlType))
      }
    val ctxDecl = ctx.bindVarList(bindings, task.text)
    val tRuntime = task.runtime.map(applyRuntime(_, ctxDecl))
    val tHints = task.hints.map(applyHints(_, ctxDecl))

    // check that all expressions can be coereced to a string inside
    // the command section
    val cmdParts = task.command.parts.map { expr =>
      val e = applyExpr(expr, Map.empty, ctxDecl)
      val valid = e.wdlType match {
        case x if isPrimitive(x)             => true
        case T_Optional(x) if isPrimitive(x) => true
        case _                               => false
      }
      if (!valid)
        throw new TypeException(
            s"Expression ${exprToString(e)} in the command section is not coercible to a string",
            expr.text,
            ctx.docSourceUrl
        )
      e
    }
    val tCommand = TAT.CommandSection(cmdParts, task.command.text)

    val (tOutputSection, ctxOutput) = task.output match {
      case None =>
        (None, ctxDecl)
      case Some(outSection) =>
        val tOutSection = applyOutputSection(outSection, ctxDecl)
        val ctx = ctxDecl.bindOutputSection(tOutSection)
        (Some(tOutSection), ctx)
    }

    val tMeta = task.meta.map(applyMeta(_, ctxOutput))
    val tParamMeta = task.parameterMeta.map(applyParamMeta(_, ctxOutput))

    // calculate the type signature of the task
    val (inputType, outputType) = calcSignature(tInputSection, tOutputSection)

    TAT.Task(
        name = task.name,
        wdlType = T_Task(task.name, inputType, outputType),
        input = tInputSection,
        output = tOutputSection,
        command = tCommand,
        declarations = tDeclarations,
        meta = tMeta,
        parameterMeta = tParamMeta,
        runtime = tRuntime,
        hints = tHints,
        text = task.text
    )
  }

  //
  // 1. all the caller arguments have to exist with the correct types
  //    in the callee
  // 2. all the compulsory callee arguments must be specified. Optionals
  //    and arguments that have defaults can be skipped.
  private def applyCall(call: AST.Call, ctx: Context): TAT.Call = {
    val callerInputs: Map[String, TAT.Expr] = call.inputs match {
      case Some(AST.CallInputs(value, _)) =>
        value.map { inp =>
          inp.name -> applyExpr(inp.expr, Map.empty, ctx)
        }.toMap
      case None => Map.empty
    }

    val callee: T_Callable = ctx.callables.get(call.name) match {
      case None =>
        throw new TypeException(s"called task/workflow ${call.name} is not defined",
                                call.text,
                                ctx.docSourceUrl)
      case Some(x: T_Callable) => x
    }

    // type-check input arguments
    callerInputs.foreach {
      case (argName, tExpr) =>
        callee.input.get(argName) match {
          case None =>
            throw new TypeException(
                s"call ${call} has argument ${argName} that does not exist in the callee",
                call.text,
                ctx.docSourceUrl
            )
          case Some((calleeType, _)) if regime == Strict =>
            if (calleeType != tExpr.wdlType)
              throw new TypeException(
                  s"argument ${argName} has wrong type ${tExpr.wdlType}, expecting ${calleeType}",
                  call.text,
                  ctx.docSourceUrl
              )
          case Some((calleeType, _)) if regime >= Moderate =>
            if (!unify.isCoercibleTo(calleeType, tExpr.wdlType))
              throw new TypeException(
                  s"argument ${argName} has type ${tExpr.wdlType}, it is not coercible to ${calleeType}",
                  call.text,
                  ctx.docSourceUrl
              )
          case _ => ()
        }
    }

    // check that all the compulsory arguments are provided
    callee.input.foreach {
      case (argName, (_, false)) =>
        callerInputs.get(argName) match {
          case None =>
            throw new TypeException(
                s"compulsory argument ${argName} to task/workflow ${call.name} is missing",
                call.text,
                ctx.docSourceUrl
            )
          case Some(_) => ()
        }
      case (_, (_, _)) =>
        // an optional argument, it may not be provided
        ()
    }

    // The name of the call may not contain dots. Examples:
    //
    // call lib.concat as concat     concat
    // call add                      add
    // call a.b.c                    c
    val actualName = call.alias match {
      case None if !(call.name contains ".") =>
        call.name
      case None =>
        val parts = call.name.split("\\.")
        parts.last
      case Some(alias) => alias.name
    }

    if (ctx.declarations contains actualName)
      throw new TypeException(s"call ${actualName} shadows an existing definition",
                              call.text,
                              ctx.docSourceUrl)

    // conver the alias to a simpler string option
    val alias = call.alias match {
      case None                         => None
      case Some(AST.CallAlias(name, _)) => Some(name)
    }

    val afters = call.afters.map { after =>
      ctx.declarations.get(after.name) match {
        case Some(c: T_Call) => c
        case Some(_) =>
          throw new TypeException(
              s"call ${actualName} after clause refers to non-call declaration ${after.name}",
              after.text,
              ctx.docSourceUrl
          )
        case None =>
          throw new TypeException(
              s"call ${actualName} after clause refers to non-existant call ${after.name}",
              after.text,
              ctx.docSourceUrl
          )
      }
    }

    TAT.Call(
        fullyQualifiedName = call.name,
        wdlType = T_Call(actualName, callee.output),
        callee = callee,
        alias = alias,
        afters = afters,
        actualName = actualName,
        inputs = callerInputs,
        text = call.text
    )
  }

  // Add types to a block of workflow-elements:
  //
  // For example:
  //   Int x = y + 4
  //   call A { input: bam_file = "u.bam" }
  //   scatter ...
  //
  // return
  //  1) type bindings for this block (x --> Int, A ---> Call, ..)
  //  2) the typed workflow elements
  private def applyWorkflowElements(
      ctx: Context,
      body: Vector[AST.WorkflowElement]
  ): (Bindings, Vector[TAT.WorkflowElement]) = {
    body.foldLeft((Map.empty[String, WdlType], Vector.empty[TAT.WorkflowElement])) {
      case ((bindings, wElements), decl: AST.Declaration) =>
        val tDecl = applyDecl(decl, bindings, ctx)
        (bindings + (tDecl.name -> tDecl.wdlType), wElements :+ tDecl)

      case ((bindings, wElements), call: AST.Call) =>
        val tCall = applyCall(call, ctx.bindVarList(bindings, call.text))
        (bindings + (tCall.actualName -> tCall.wdlType), wElements :+ tCall)

      case ((bindings, wElements), subSct: AST.Scatter) =>
        // a nested scatter
        val (tScatter, sctBindings) = applyScatter(subSct, ctx.bindVarList(bindings, subSct.text))
        (bindings ++ sctBindings, wElements :+ tScatter)

      case ((bindings, wElements), cond: AST.Conditional) =>
        // a nested conditional
        val (tCond, condBindings) = applyConditional(cond, ctx.bindVarList(bindings, cond.text))
        (bindings ++ condBindings, wElements :+ tCond)
    }
  }

  // The body of the scatter becomes accessible to statements that come after it.
  // The iterator is not visible outside the scatter body.
  //
  // for (i in [1, 2, 3]) {
  //    call A
  // }
  //
  // Variable "i" is not visible after the scatter completes.
  // A's members are arrays.
  private def applyScatter(scatter: AST.Scatter, ctxOuter: Context): (TAT.Scatter, Bindings) = {
    val eCollection = applyExpr(scatter.expr, Map.empty, ctxOuter)
    val elementType = eCollection.wdlType match {
      case T_Array(elementType, _) => elementType
      case _ =>
        throw new TypeException(s"Collection in scatter (${scatter}) is not an array type",
                                scatter.text,
                                ctxOuter.docSourceUrl)
    }
    // add a binding for the iteration variable
    //
    // The iterator identifier is not exported outside the scatter
    val ctxInner = ctxOuter.bindVar(scatter.identifier, elementType, scatter.text)
    val (bindings, tElements) = applyWorkflowElements(ctxInner, scatter.body)
    assert(!(bindings contains scatter.identifier))

    // Add an array type to all variables defined in the scatter body
    val bindingsWithArray =
      bindings.map {
        case (callName, callType: T_Call) =>
          val callOutput = callType.output.map {
            case (name, t) => name -> T_Array(t)
          }
          callName -> T_Call(callType.name, callOutput)
        case (varName, typ: T) =>
          varName -> T_Array(typ)
      }
    (TAT.Scatter(scatter.identifier, eCollection, tElements, scatter.text), bindingsWithArray)
  }

  // Ensure that a type is optional, but avoid making it doubly so.
  // For example:
  //   Int --> Int?
  //   Int? --> Int?
  // Avoid this:
  //   Int?  --> Int??
  private def makeOptional(t: WdlType): WdlType = {
    t match {
      case T_Optional(x) => x
      case x             => T_Optional(x)
    }
  }

  // The body of a conditional is accessible to the statements that come after it.
  // This is different than the scoping rules for other programming languages.
  //
  // Add an optional modifier to all the types inside the body.
  private def applyConditional(cond: AST.Conditional,
                               ctxOuter: Context): (TAT.Conditional, Bindings) = {
    val condExpr = applyExpr(cond.expr, Map.empty, ctxOuter)
    if (condExpr.wdlType != T_Boolean)
      throw new TypeException(s"Expression ${exprToString(condExpr)} must have boolean type",
                              cond.text,
                              ctxOuter.docSourceUrl)

    // keep track of the inner/outer bindings. Within the block we need [inner],
    // [outer] is what we produce, which has the optional modifier applied to
    // everything.
    val (bindings, wfElements) = applyWorkflowElements(ctxOuter, cond.body)

    val bindingsWithOpt =
      bindings.map {
        case (callName, callType: T_Call) =>
          val callOutput = callType.output.map {
            case (name, t) => name -> makeOptional(t)
          }
          callName -> T_Call(callType.name, callOutput)
        case (varName, typ: WdlType) =>
          varName -> makeOptional(typ)
      }
    (TAT.Conditional(condExpr, wfElements, cond.text), bindingsWithOpt)
  }

  private def applyWorkflow(wf: AST.Workflow, ctxOuter: Context): TAT.Workflow = {
    val (tInputSection, ctx) = wf.input match {
      case None =>
        (None, ctxOuter)
      case Some(inpSection) =>
        val tInpSection = applyInputSection(inpSection, ctxOuter)
        val ctx = ctxOuter.bindInputSection(tInpSection)
        (Some(tInpSection), ctx)
    }

    val (bindings, wfElements) = applyWorkflowElements(ctx, wf.body)
    val ctxBody = ctx.bindVarList(bindings, wf.text)

    val (tOutputSection, ctxOutput) = wf.output match {
      case None =>
        (None, ctxBody)
      case Some(outSection) =>
        val tOutSection = applyOutputSection(outSection, ctxBody)
        val ctx = ctxBody.bindOutputSection(tOutSection)
        (Some(tOutSection), ctx)
    }

    val tMeta = wf.meta.map(applyMeta(_, ctxOutput))
    val tParamMeta = wf.parameterMeta.map(applyParamMeta(_, ctxOutput))

    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(tInputSection, tOutputSection)
    TAT.Workflow(
        name = wf.name,
        wdlType = T_Workflow(wf.name, inputType, outputType),
        input = tInputSection,
        output = tOutputSection,
        meta = tMeta,
        parameterMeta = tParamMeta,
        body = wfElements,
        text = wf.text
    )
  }

  // Convert from AST to TAT and maintain context
  private def applyDoc(doc: AST.Document): (TAT.Document, Context) = {
    val initCtx = Context(
        version = doc.version.value,
        stdlib = Stdlib(conf, doc.version.value, Some(doc.sourceUrl)),
        docSourceUrl = Some(doc.sourceUrl)
    )

    // translate each of the elements in the document
    val (context, elements) =
      doc.elements.foldLeft((initCtx, Vector.empty[TAT.DocumentElement])) {
        case ((ctx, elems), task: AST.Task) =>
          val task2 = applyTask(task, ctx)
          (ctx.bindCallable(task2.wdlType, task.text), elems :+ task2)

        case ((ctx, elems), iStat: AST.ImportDoc) =>
          // recurse into the imported document, add types
          val (iDoc, iCtx) = applyDoc(iStat.doc.get)

          // Figure out what to name the sub-document
          val namespace = iStat.name match {
            case None =>
              // Something like
              //    import "http://example.com/lib/stdlib"
              //    import "A/B/C"
              // Where the user does not specify an alias. The namespace
              // will be named:
              //    stdlib
              //    C
              val url = UUtil.getUrl(iStat.addr.value, conf.localDirectories)
              val nsName = Paths.get(url.getFile).getFileName.toString
              if (nsName.endsWith(".wdl"))
                nsName.dropRight(".wdl".length)
              else
                nsName
            case Some(x) => x.value
          }

          val aliases = iStat.aliases.map {
            case AST.ImportAlias(id1, id2, text) =>
              TAT.ImportAlias(id1, id2, ctx.structs(id1), text)
          }
          val importDoc =
            TAT.ImportDoc(iStat.name.map(_.value), aliases, iStat.addr.value, iDoc, iStat.text)

          // add the externally visible definitions to the context
          (ctx.bindImportedDoc(namespace, iCtx, iStat.aliases, iStat.text), elems :+ importDoc)

        case ((ctx, elems), struct: AST.TypeStruct) =>
          // Add the struct to the context
          val tStruct = typeFromAst(struct, struct.text, ctx).asInstanceOf[T_Struct]
          val defStruct = TAT.StructDefinition(struct.name, tStruct.members, struct.text)
          (ctx.bindStruct(tStruct, struct.text), elems :+ defStruct)
      }

    // now that we have types for everything else, we can check the workflow
    val (tWf, contextFinal) = doc.workflow match {
      case None => (None, context)
      case Some(wf) =>
        val tWf = applyWorkflow(wf, context)
        val ctxFinal = context.bindCallable(tWf.wdlType, wf.text)
        (Some(tWf), ctxFinal)
    }

    val tDoc = TAT.Document(doc.sourceUrl,
                            doc.sourceCode,
                            TAT.Version(doc.version.value, doc.version.text),
                            elements,
                            tWf,
                            doc.text,
                            doc.comments)
    (tDoc, contextFinal)
  }

  // Main entry point
  //
  // check if the WDL document is correctly typed. Otherwise, throw an exception
  // describing the problem in a human readable fashion. Return a document
  // with types.
  //
  def apply(doc: AST.Document): (TAT.Document, Context) = {
    //val (tDoc, _) = applyDoc(doc)
    //tDoc
    applyDoc(doc)
  }
}
