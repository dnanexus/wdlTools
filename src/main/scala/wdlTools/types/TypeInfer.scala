package wdlTools.types

import java.nio.file.Paths

import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.TextSource
import wdlTools.syntax.{Util => SUtil}
import wdlTools.types.TypedAbstractSyntax.ExprInvalid
import wdlTools.types.WdlTypes._
import wdlTools.types.Util.{exprToString, isPrimitive, typeToString}
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

      case (t, _) =>
        val msg = s"Expressions ${exprToString(a)} and ${exprToString(b)} cannot be added"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(t))
        }
    }
    t
  }

  // math operation on a single argument
  private def typeEvalMathOp(expr: TAT.Expr, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Int   => T_Int
      case T_Float => T_Float
      case other =>
        val msg = s"${exprToString(expr)} must be an integer or a float"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(other))
        }
    }
  }

  private def typeEvalMathOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Int, T_Int)     => T_Int
      case (T_Float, T_Int)   => T_Float
      case (T_Int, T_Float)   => T_Float
      case (T_Float, T_Float) => T_Float
      case (t, _) =>
        val msg =
          s"Expressions ${exprToString(a)} and ${exprToString(b)} must be integers or floats"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(t))
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
          T_Invalid(msg, Some(other))
        }
    }
  }

  private def typeEvalLogicalOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Boolean, T_Boolean) => T_Boolean
      case (t, _) =>
        val msg = s"${exprToString(a)} and ${exprToString(b)} must have boolean type"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(t))
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
      case (t, _) =>
        val msg = s"Expressions ${exprToString(a)} and ${exprToString(b)} must have the same type"
        if (errorAsException) {
          throw new TypeException(msg, a.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(t))
        }
    }
  }

  private def typeEvalExprGetName(expr: TAT.Expr, id: String, ctx: Context): WdlType = {
    expr.wdlType match {
      case struct: T_Struct =>
        struct.members.get(id) match {
          case None =>
            val msg = s"Struct ${struct.name} does not have member ${id} in expression"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg, Some(expr.wdlType))
            }
          case Some(t) =>
            t
        }
      case call: T_Call =>
        call.output.get(id) match {
          case None =>
            val msg = s"Call object ${call.name} does not have output ${id} in expression"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg, Some(expr.wdlType))
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
        ctx.aliases.get(structName) match {
          case None =>
            val msg = s"unknown struct ${structName}"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg, Some(expr.wdlType))
            }
          case Some(struct: T_Struct) =>
            struct.members.get(id) match {
              case None =>
                val msg = s"Struct ${structName} does not have member ${id}"
                if (errorAsException) {
                  throw new TypeException(msg, expr.text, ctx.docSourceUrl)
                } else {
                  T_Invalid(msg, Some(expr.wdlType))
                }
              case Some(t) => t
            }
          case other =>
            val msg = s"not a struct ${other}"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg, Some(expr.wdlType))
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
          T_Invalid(msg, Some(expr.wdlType))
        }

      case _ =>
        val msg = s"member access (${id}) in expression is illegal"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          T_Invalid(msg, Some(expr.wdlType))
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
          T_Invalid(msg, vec.headOption)
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
        val t = ctx.lookup(id, bindings) match {
          case None =>
            val msg = s"Identifier ${id} is not defined"
            if (errorAsException) {
              throw new TypeException(msg, expr.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg)
            }
          case Some(t) =>
            t
        }
        TAT.ExprIdentifier(id, t, text)

      // All the sub-exressions have to be strings, or coercible to strings
      case AST.ExprCompoundString(vec, text) =>
        val initialType: T = T_String
        val (vec2, wdlType) = vec.foldLeft((Vector.empty[TAT.Expr], initialType)) {
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
                T_Invalid(msg, Some(e2.wdlType))
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
                T_Invalid(msg, tVecExprs.headOption.map(_.wdlType))
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
            T_Invalid(msg, Some(te.wdlType))
          }
        } else if (ve.wdlType != T_Boolean) {
          val msg =
            s"condition ${exprToString(ve)} should have boolean type, it has type ${typeToString(ve.wdlType)} instead"
          if (errorAsException) {
            throw new TypeException(msg, expr.text, ctx.docSourceUrl)
          } else {
            T_Invalid(msg, Some(ve.wdlType))
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
              T_Invalid(msg, Some(ve.wdlType))
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
              T_Invalid(msg, Some(other))
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
              T_Invalid(msg, Some(eIndex.wdlType))
            }
          case (_, _) =>
            val msg = s"expression ${exprToString(eArray)} must be an array"
            if (errorAsException) {
              throw new TypeException(msg, eArray.text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg, Some(eArray.wdlType))
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
              T_Invalid(msg, Some(eCond.wdlType))
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
                  T_Invalid(msg, Some(eTrueBranch.wdlType))
                }
            }
          }
        }
        TAT.ExprIfThenElse(eCond, eTrueBranch, eFalseBranch, t, text)

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case AST.ExprApply(funcName: String, elements: Vector[AST.Expr], text) =>
        val eElements = elements.map(applyExpr(_, bindings, ctx))
        try {
          val (outputType, funcSig) = ctx.stdlib.apply(funcName, eElements.map(_.wdlType))
          TAT.ExprApply(funcName, funcSig, eElements, outputType, text)
        } catch {
          case e: StdlibFunctionException if errorAsException =>
            throw new TypeException(e.getMessage, expr.text, ctx.docSourceUrl)
          case e: StdlibFunctionException =>
            TAT.ExprInvalid(T_Invalid(e.getMessage), None, expr.text)
        }

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
        ctx.aliases.get(id) match {
          case None =>
            val msg = s"struct ${id} has not been defined"
            if (errorAsException) {
              throw new TypeException(msg, text, ctx.docSourceUrl)
            } else {
              T_Invalid(msg)
            }
          case Some(struct) => struct
        }
      case _: AST.TypeObject => T_Object
      case AST.TypeStruct(name, members, _) =>
        T_StructDef(name, members.map {
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

    val lhsType = typeFromAst(decl.wdlType, decl.text, ctx)
    val tDecl = decl.expr match {
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
            T_Invalid(msg, Some(lhsType))
          }
        }
        TAT.Declaration(decl.name, wdlType, Some(e), decl.text)
    }

    // There are cases where we want to allow shadowing. For example, it
    // is legal to have an output variable with the same name as an input variable.
    if (!canShadow && ctx.lookup(decl.name, bindings).isDefined) {
      val msg = s"variable ${decl.name} shadows an existing variable"
      val wdlType = if (errorAsException) {
        throw new TypeException(msg, decl.text, ctx.docSourceUrl)
      } else {
        T_Invalid(msg, Some(tDecl.wdlType))
      }
      TAT.Declaration(tDecl.name, wdlType, tDecl.expr, tDecl.text)
    } else {
      tDecl
    }
  }

  // type check the input section, and see that there are no double definitions.
  // return input definitions
  //
  private def applyInputSection(inputSection: AST.InputSection,
                                ctx: Context): Vector[TAT.InputDefinition] = {
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
                  TAT.Declaration(d.name, T_Invalid(msg, Some(d.wdlType)), d.expr, d.text)
                }
              case d => d
            }
            (tDecls :+ tDecl, names + decl.name, bindings + (tDecl.name -> tDecl.wdlType))
        }

    // convert the typed declarations into input definitions
    tDecls.map { tDecl =>
      // What kind of input is this?
      // compulsory, optional, one with a default
      tDecl.expr match {
        case None => {
          tDecl.wdlType match {
            case t: WdlTypes.T_Optional =>
              TAT.OptionalInputDefinition(tDecl.name, t, tDecl.text)
            case _ =>
              TAT.RequiredInputDefinition(tDecl.name, tDecl.wdlType, tDecl.text)
          }
        }
        case Some(expr) =>
          TAT.OverridableInputDefinitionWithDefault(tDecl.name, tDecl.wdlType, expr, tDecl.text)
      }
    }
  }

  // Calculate types for the outputs, and return a new typed output section
  private def applyOutputSection(outputSection: AST.OutputSection,
                                 ctx: Context): Vector[TAT.OutputDefinition] = {
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
                  TAT.Declaration(d.name, T_Invalid(msg, Some(d.wdlType)), d.expr, d.text)
                }
              case d if both.contains(d.name) =>
                val msg = s"Definition ${d.name} shadows exisiting declarations"
                if (errorAsException) {
                  throw new TypeException(msg, d.text, ctx.docSourceUrl)
                } else {
                  TAT.Declaration(d.name, T_Invalid(msg, Some(d.wdlType)), d.expr, d.text)
                }
              case d => d
            }
            val bindings2 = bindings + (tDecl.name -> tDecl.wdlType)
            (tDecls :+ tDecl, names + tDecl.name, bindings2)
        }

    // convert the declarations into output definitions
    tDecls.map { tDecl =>
      tDecl.expr match {
        case None =>
          throw new TypeException("Outputs must have expressions", tDecl.text, ctx.docSourceUrl)
        case Some(expr) =>
          TAT.OutputDefinition(tDecl.name, tDecl.wdlType, expr, tDecl.text)
      }
    }
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(
      inputSection: Vector[TAT.InputDefinition],
      outputSection: Vector[TAT.OutputDefinition]
  ): (Map[String, (WdlType, Boolean)], Map[String, WdlType]) = {
    val inputType: Map[String, (WdlType, Boolean)] = inputSection.map {
      case d: TAT.RequiredInputDefinition =>
        // input is compulsory
        d.name -> (d.wdlType, false)
      case d: TAT.OverridableInputDefinitionWithDefault =>
        // input has a default value, caller may omit it.
        d.name -> (d.wdlType, true)
      case d: TAT.OptionalInputDefinition =>
        // input is optional, caller can omit it.
        d.name -> (d.wdlType, true)
    }.toMap

    val outputType: Map[String, WdlType] = outputSection.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap

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
      case AST.ValueNull(text)                          => TAT.MetaNull(text)
      case AST.ValueBoolean(value, text)                => TAT.MetaBoolean(value, text)
      case AST.ValueInt(value, text)                    => TAT.MetaInt(value, text)
      case AST.ValueFloat(value, text)                  => TAT.MetaFloat(value, text)
      case AST.ValueString(value, text)                 => TAT.MetaString(value, text)
      case AST.ExprIdentifier(id, text) if id == "null" => TAT.MetaNull(text)
      case AST.ExprIdentifier(id, text)                 => TAT.MetaString(id, text)
      case AST.ExprArray(vec, text)                     => TAT.MetaArray(vec.map(metaValueFromExpr(_, ctx)), text)
      case AST.ExprMap(members, text) =>
        TAT.MetaObject(
            members.map {
              case AST.ExprMapItem(AST.ValueString(key, _), value, _) =>
                key -> metaValueFromExpr(value, ctx)
              case AST.ExprMapItem(AST.ExprIdentifier(key, _), value, _) =>
                key -> metaValueFromExpr(value, ctx)
              case other =>
                throw new RuntimeException(s"bad value ${SUtil.exprToString(other)}")
            }.toMap,
            text
        )
      case AST.ExprObject(members, text) =>
        TAT.MetaObject(
            members.map {
              case AST.ExprObjectMember(key, value, _) =>
                key -> metaValueFromExpr(value, ctx)
              case other =>
                throw new RuntimeException(s"bad value ${SUtil.exprToString(other)}")
            }.toMap,
            text
        )
      case other =>
        val msg = s"${SUtil.exprToString(other)} is an invalid meta value"
        if (errorAsException) {
          throw new TypeException(msg, expr.text, ctx.docSourceUrl)
        } else {
          TAT.MetaInvalid(msg, other.text)
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
              TAT.MetaInvalid(msg, kv.expr.text)
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
    val (inputDefs, ctx) = task.input match {
      case None =>
        (Vector.empty, ctxOuter)
      case Some(inpSection) =>
        val tInpSection = applyInputSection(inpSection, ctxOuter)
        val ctx = ctxOuter.bindInputSection(tInpSection)
        (tInpSection, ctx)
    }

    // add types to the declarations, and accumulate context
    val (tRawDecl, _) =
      task.declarations.foldLeft((Vector.empty[TAT.Declaration], Bindings.empty)) {
        case ((tDecls, bindings), decl) =>
          val tDecl = applyDecl(decl, bindings, ctx)
          (tDecls :+ tDecl, bindings + (tDecl.name -> tDecl.wdlType))
      }
    val (tDeclarations, ctxDecl) = tRawDecl.foldLeft((Vector.empty[TAT.Declaration], ctx)) {
      case ((tDecls, curCtx), tDecl) =>
        try {
          (tDecls :+ tDecl, curCtx.bindVar(tDecl.name, tDecl.wdlType))
        } catch {
          case e: DuplicateDeclarationException if errorAsException =>
            throw new TypeException(e.getMessage, tDecl.text, ctx.docSourceUrl)
          case e: DuplicateDeclarationException =>
            val errDecl = TAT.Declaration(tDecl.name,
                                          T_Invalid(e.getMessage, Some(tDecl.wdlType)),
                                          tDecl.expr,
                                          tDecl.text)
            (tDecls :+ errDecl, curCtx)
        }
    }

    val tRuntime = task.runtime.map(applyRuntime(_, ctxDecl))
    val tHints = task.hints.map(applyHints(_, ctxDecl))

    // check that all expressions can be coereced to a string inside
    // the command section
    val cmdParts = task.command.parts.map { expr =>
      val e = applyExpr(expr, Map.empty, ctxDecl)
      e.wdlType match {
        case x if isPrimitive(x)             => e
        case T_Optional(x) if isPrimitive(x) => e
        case _ =>
          val msg =
            s"Expression ${exprToString(e)} in the command section is not coercible to a string"
          if (errorAsException) {
            throw new TypeException(msg, expr.text, ctx.docSourceUrl)
          } else {
            TAT.ExprInvalid(T_Invalid(msg, Some(e.wdlType)), Some(e), e.text)
          }
      }
    }
    val tCommand = TAT.CommandSection(cmdParts, task.command.text)

    val (outputDefs, ctxOutput) = task.output match {
      case None =>
        (Vector.empty, ctxDecl)
      case Some(outSection) =>
        val tOutSection = applyOutputSection(outSection, ctxDecl)
        val ctx = ctxDecl.bindOutputSection(tOutSection)
        (tOutSection, ctx)
    }

    val tMeta = task.meta.map(applyMeta(_, ctxOutput))
    val tParamMeta = task.parameterMeta.map(applyParamMeta(_, ctxOutput))

    // calculate the type signature of the task
    val (inputType, outputType) = calcSignature(inputDefs, outputDefs)

    TAT.Task(
        name = task.name,
        wdlType = T_TaskDef(task.name, inputType, outputType),
        inputs = inputDefs,
        outputs = outputDefs,
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

    // check that the call refers to a valid callee
    val callee: T_Callable = ctx.callables.get(call.name) match {
      case None =>
        val msg = s"called task/workflow ${call.name} is not defined"
        if (errorAsException) {
          throw new TypeException(msg, call.text, ctx.docSourceUrl)
        } else {
          T_CallableInvalid(call.name, msg, None)
        }
      case Some(x: T_Callable) => x
    }

    // check if the call shadows an existing call
    val callType = T_CallDef(actualName, callee.output)
    val wdlType = if (ctx.declarations contains actualName) {
      val msg = s"call ${actualName} shadows an existing definition"
      if (errorAsException) {
        throw new TypeException(msg, call.text, ctx.docSourceUrl)
      } else {
        T_CallInvalid(call.name, msg, Some(callType))
      }
    } else {
      callType
    }

    // check that any afters refer to valid calls
    val afters = call.afters.map { after =>
      ctx.declarations.get(after.name) match {
        case Some(c: T_Call) => c
        case Some(_) =>
          val msg = s"call ${actualName} after clause refers to non-call declaration ${after.name}"
          if (errorAsException) {
            throw new TypeException(msg, after.text, ctx.docSourceUrl)
          } else {
            T_CallInvalid(after.name, msg, None)
          }
        case None =>
          val msg = s"call ${actualName} after clause refers to non-existant call ${after.name}"
          if (errorAsException) {
            throw new TypeException(msg, after.text, ctx.docSourceUrl)
          } else {
            T_CallInvalid(after.name, msg, None)
          }
      }
    }

    // convert inputs
    val callerInputs: Map[String, TAT.Expr] = call.inputs match {
      case Some(AST.CallInputs(value, _)) =>
        value.map { inp =>
          val argName = inp.name
          val tExpr = applyExpr(inp.expr, Map.empty, ctx)
          // type-check input argument
          val errorMsg = callee.input.get(argName) match {
            case None =>
              Some(s"call ${call} has argument ${argName} that does not exist in the callee")
            case Some((calleeType, _)) if regime == Strict && calleeType != tExpr.wdlType =>
              Some(s"argument ${argName} has wrong type ${tExpr.wdlType}, expecting ${calleeType}")
            case Some((calleeType, _))
                if regime >= Moderate && !unify.isCoercibleTo(calleeType, tExpr.wdlType) =>
              Some(
                  s"argument ${argName} has type ${tExpr.wdlType}, it is not coercible to ${calleeType}"
              )
            case _ => None
          }
          if (errorMsg.isEmpty) {
            argName -> tExpr
          } else if (errorAsException) {
            throw new TypeException(errorMsg.get, call.text, ctx.docSourceUrl)
          } else {
            argName -> TAT.ExprInvalid(T_Invalid(errorMsg.get, Some(tExpr.wdlType)),
                                       Some(tExpr),
                                       tExpr.text)
          }
        }.toMap
      case None => Map.empty
    }

    // check that all the compulsory arguments are provided
    val missingInputs = callee.input.flatMap {
      case (argName, (_, false)) =>
        callerInputs.get(argName) match {
          case None =>
            val msg = s"compulsory argument ${argName} to task/workflow ${call.name} is missing"
            if (errorAsException) {
              throw new TypeException(msg, call.text, ctx.docSourceUrl)
            } else {
              Some(argName -> TAT.ExprInvalid(T_Invalid(msg), None, call.text))
            }
          case Some(_) => None
        }
      case (_, (_, _)) =>
        // an optional argument, it may not be provided
        None
    }

    // convert the alias to a simpler string option
    val alias = call.alias match {
      case None                         => None
      case Some(AST.CallAlias(name, _)) => Some(name)
    }

    TAT.Call(
        fullyQualifiedName = call.name,
        wdlType = wdlType,
        callee = callee,
        alias = alias,
        afters = afters,
        actualName = actualName,
        inputs = callerInputs ++ missingInputs,
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
    body.foldLeft((Bindings.empty, Vector.empty[TAT.WorkflowElement])) {
      case ((bindings, wElements), decl: AST.Declaration) =>
        val tDecl = applyDecl(decl, bindings, ctx)
        (bindings + (tDecl.name -> tDecl.wdlType), wElements :+ tDecl)

      case ((bindings, wElements), call: AST.Call) =>
        val tCall = applyCall(call, ctx.bindVarList(bindings))
        (bindings + (tCall.actualName -> tCall.wdlType), wElements :+ tCall)

      case ((bindings, wElements), subSct: AST.Scatter) =>
        // a nested scatter
        val (tScatter, sctBindings) = applyScatter(subSct, ctx.bindVarList(bindings))
        (bindings ++ sctBindings, wElements :+ tScatter)

      case ((bindings, wElements), cond: AST.Conditional) =>
        // a nested conditional
        val (tCond, condBindings) = applyConditional(cond, ctx.bindVarList(bindings))
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
      case other =>
        val msg = s"Collection in scatter (${scatter}) is not an array type"
        if (errorAsException) {
          throw new TypeException(msg, scatter.text, ctxOuter.docSourceUrl)
        } else {
          T_Invalid(msg, Some(other))
        }
    }
    // add a binding for the iteration variable
    //
    // The iterator identifier is not exported outside the scatter
    val (ctxInner, wdlType) =
      try {
        (ctxOuter.bindVar(scatter.identifier, elementType), elementType)
      } catch {
        case e: DuplicateDeclarationException if errorAsException =>
          throw new TypeException(e.getMessage, scatter.text, ctxOuter.docSourceUrl)
        case e: DuplicateDeclarationException =>
          (ctxOuter, T_Invalid(e.getMessage, Some(elementType)))
      }

    val (bindings, tElements) = applyWorkflowElements(ctxInner, scatter.body)
    assert(!(bindings contains scatter.identifier))

    // Add an array type to all variables defined in the scatter body
    val bindingsWithArray =
      bindings.map {
        case (callName, callType: T_Call) =>
          val callOutput = callType.output.map {
            case (name, t) => name -> T_Array(t)
          }
          callName -> T_CallDef(callType.name, callOutput)
        case (varName, typ: T) =>
          varName -> T_Array(typ)
      }

    val tScatter = TAT.Scatter(scatter.identifier, wdlType, eCollection, tElements, scatter.text)
    (tScatter, bindingsWithArray)
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
    val condExpr = applyExpr(cond.expr, Map.empty, ctxOuter) match {
      case e if e.wdlType == T_Boolean => e
      case e =>
        val msg = s"Expression ${exprToString(e)} must have boolean type"
        if (errorAsException) {
          throw new TypeException(msg, cond.text, ctxOuter.docSourceUrl)
        } else {
          ExprInvalid(T_Invalid(msg, Some(e.wdlType)), Some(e), e.text)
        }
    }

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
          callName -> T_CallDef(callType.name, callOutput)
        case (varName, typ: WdlType) =>
          varName -> makeOptional(typ)
      }
    (TAT.Conditional(condExpr, wfElements, cond.text), bindingsWithOpt)
  }

  private def applyWorkflow(wf: AST.Workflow, ctxOuter: Context): TAT.Workflow = {
    val (inputDefs, ctx) = wf.input match {
      case None =>
        (Vector.empty, ctxOuter)
      case Some(inpSection) =>
        val tInpSection = applyInputSection(inpSection, ctxOuter)
        val ctx = ctxOuter.bindInputSection(tInpSection)
        (tInpSection, ctx)
    }

    val (bindings, wfElements) = applyWorkflowElements(ctx, wf.body)
    val ctxBody = ctx.bindVarList(bindings)

    val (outputDefs, ctxOutput) = wf.output match {
      case None =>
        (Vector.empty, ctxBody)
      case Some(outSection) =>
        val tOutSection = applyOutputSection(outSection, ctxBody)
        val ctx = ctxBody.bindOutputSection(tOutSection)
        (tOutSection, ctx)
    }

    val tMeta = wf.meta.map(applyMeta(_, ctxOutput))
    val tParamMeta = wf.parameterMeta.map(applyParamMeta(_, ctxOutput))

    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(inputDefs, outputDefs)
    TAT.Workflow(
        name = wf.name,
        wdlType = T_WorkflowDef(wf.name, inputType, outputType),
        inputs = inputDefs,
        outputs = outputDefs,
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
        stdlib = Stdlib(conf, doc.version.value),
        docSourceUrl = Some(doc.sourceUrl)
    )

    // translate each of the elements in the document
    val (context, elements) =
      doc.elements.foldLeft((initCtx, Vector.empty[TAT.DocumentElement])) {
        case ((ctx, elems), task: AST.Task) =>
          val task2 = applyTask(task, ctx)
          try {
            (ctx.bindCallable(task2.wdlType), elems :+ task2)
          } catch {
            case e: DuplicateDeclarationException if errorAsException =>
              throw new TypeException(e.getMessage, task.text, ctx.docSourceUrl)
            case e: DuplicateDeclarationException =>
              val errTask =
                task2.copy(wdlType = T_TaskInvalid(e.getMessage, task2.wdlType))
              (ctx, elems :+ errTask)
          }

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
              TAT.ImportAlias(id1, id2, ctx.aliases(id1), text)
          }
          val name = iStat.name.map(_.value)
          val addr = iStat.addr.value
          val actualName = name.getOrElse(UUtil.getFilename(addr).replace(".wdl", ""))
          val importDoc =
            TAT.ImportDoc(name, T_DocumentDef(actualName), aliases, addr, iDoc, iStat.text)

          // add the externally visible definitions to the context
          try {
            (ctx.bindImportedDoc(namespace, iCtx, iStat.aliases), elems :+ importDoc)
          } catch {
            case e: DuplicateDeclarationException if errorAsException =>
              throw new TypeException(e.getMessage, importDoc.text, ctx.docSourceUrl)
            case e: DuplicateDeclarationException =>
              val errDoc =
                importDoc.copy(wdlType = T_DocumentInvalid(e.getMessage, importDoc.wdlType))
              (ctx, elems :+ errDoc)
          }

        case ((ctx, elems), struct: AST.TypeStruct) =>
          // Add the struct to the context
          val tStruct = typeFromAst(struct, struct.text, ctx).asInstanceOf[T_Struct]
          val defStruct = TAT.StructDefinition(struct.name, tStruct, tStruct.members, struct.text)
          try {
            (ctx.bindStruct(tStruct), elems :+ defStruct)
          } catch {
            case e: DuplicateDeclarationException if errorAsException =>
              throw new TypeException(e.getMessage, struct.text, ctx.docSourceUrl)
            case e: DuplicateDeclarationException =>
              val errStruct =
                defStruct.copy(wdlType = T_StructInvalid(e.getMessage, defStruct.wdlType))
              (ctx, elems :+ errStruct)
          }
      }

    // now that we have types for everything else, we can check the workflow
    val (tWf, contextFinal) = doc.workflow match {
      case None => (None, context)
      case Some(wf) =>
        val tWf = applyWorkflow(wf, context)
        val (tWfFinal, ctxFinal) =
          try {
            (tWf, context.bindCallable(tWf.wdlType))
          } catch {
            case e: DuplicateDeclarationException if errorAsException =>
              throw new TypeException(e.getMessage, wf.text, context.docSourceUrl)
            case e: DuplicateDeclarationException =>
              val errWf = tWf.copy(wdlType = T_WorkflowInvalid(e.getMessage, tWf.wdlType))
              (errWf, context)
          }

        (Some(tWfFinal), ctxFinal)
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
