package wdlTools.types

import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.TextSource
import wdlTools.syntax.{Util => SUtil}
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.types.WdlTypes._
import wdlTools.types.Util.{exprToString, isPrimitive, typeToString}
import wdlTools.util.TypeCheckingRegime._
import wdlTools.util.{Util => UUtil}

/**
  * Type inference
  * @param conf options
  * @param errorHandler optional error handler function. If defined, it is called every time a type-checking
  *                     error is encountered. If it returns false, type inference will proceed even if it
  *                     results in an invalid AST. If errorHandler is not defined or when it returns false,
  *                     a TypeException is thrown.
  */
case class TypeInfer(conf: TypeOptions,
                     errorHandler: Option[(String, TextSource, Context) => Boolean] = None) {
  private val unify = Unification(conf)
  private val regime = conf.typeChecking

  // A group of bindings. This is typically a part of the context. For example,
  // the body of a scatter.
  type WdlType = WdlTypes.T
  type Bindings = Map[String, WdlType]
  object Bindings {
    val empty = Map.empty[String, WdlType]
  }

  private def handleError(reason: String, textSource: TextSource, ctx: Context): Unit = {
    if (errorHandler.forall(eh => eh(reason, textSource, ctx))) {
      throw new TypeException(reason, textSource, ctx.docSourceUrl)
    }
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
        handleError(s"Expressions ${exprToString(a)} and ${exprToString(b)} cannot be added",
                    a.text,
                    ctx)
        t
    }
    t
  }

  // math operation on a single argument
  private def typeEvalMathOp(expr: TAT.Expr, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Int   => T_Int
      case T_Float => T_Float
      case other =>
        handleError(s"${exprToString(expr)} must be an integer or a float", expr.text, ctx)
        other
    }
  }

  private def typeEvalMathOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Int, T_Int)     => T_Int
      case (T_Float, T_Int)   => T_Float
      case (T_Int, T_Float)   => T_Float
      case (T_Float, T_Float) => T_Float
      case (t, _) =>
        handleError(
            s"Expressions ${exprToString(a)} and ${exprToString(b)} must be integers or floats",
            a.text,
            ctx
        )
        t
    }
  }

  private def typeEvalLogicalOp(expr: TAT.Expr, ctx: Context): WdlType = {
    expr.wdlType match {
      case T_Boolean => T_Boolean
      case other =>
        handleError(s"${exprToString(expr)} must be a boolean, it is ${typeToString(other)}",
                    expr.text,
                    ctx)
        other
    }
  }

  private def typeEvalLogicalOp(a: TAT.Expr, b: TAT.Expr, ctx: Context): WdlType = {
    (a.wdlType, b.wdlType) match {
      case (T_Boolean, T_Boolean) => T_Boolean
      case (t, _) =>
        handleError(s"${exprToString(a)} and ${exprToString(b)} must have boolean type",
                    a.text,
                    ctx)
        t
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
        handleError(
            s"Expressions ${exprToString(a)} and ${exprToString(b)} must have the same type",
            a.text,
            ctx
        )
        t
    }
  }

  private def typeEvalExprGetName(expr: TAT.Expr, id: String, ctx: Context): WdlType = {
    expr.wdlType match {
      case struct: T_Struct =>
        struct.members.get(id) match {
          case None =>
            handleError(s"Struct ${struct.name} does not have member ${id} in expression",
                        expr.text,
                        ctx)
            expr.wdlType
          case Some(t) =>
            t
        }
      case call: T_Call =>
        call.output.get(id) match {
          case None =>
            handleError(s"Call object ${call.name} does not have output ${id} in expression",
                        expr.text,
                        ctx)
            expr.wdlType
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
            handleError(s"unknown struct ${structName}", expr.text, ctx)
            expr.wdlType
          case Some(struct: T_Struct) =>
            struct.members.get(id) match {
              case None =>
                handleError(s"Struct ${structName} does not have member ${id}", expr.text, ctx)
                expr.wdlType
              case Some(t) => t
            }
          case other =>
            handleError(s"not a struct ${other}", expr.text, ctx)
            expr.wdlType
        }

      // accessing a pair element
      case T_Pair(l, _) if id.toLowerCase() == "left"  => l
      case T_Pair(_, r) if id.toLowerCase() == "right" => r
      case T_Pair(_, _) =>
        handleError(s"accessing a pair with (${id}) is illegal", expr.text, ctx)
        expr.wdlType

      case _ =>
        handleError(s"member access (${id}) in expression is illegal", expr.text, ctx)
        expr.wdlType
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
        handleError(errMsg + " must have the same type, or be coercible to one", textSource, ctx)
        vec.head
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
            handleError(s"Identifier ${id} is not defined", expr.text, ctx)
            T_Any
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
              handleError(
                  s"expression ${exprToString(e2)} of type ${e2.wdlType} is not coercible to string",
                  expr.text,
                  ctx
              )
              if (t == initialType) {
                e2.wdlType
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
              handleError("array elements must have the same type, or be coercible to one",
                          expr.text,
                          ctx)
              tVecExprs.head.wdlType
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
          handleError(
              s"subexpressions ${exprToString(te)} and ${exprToString(fe)} must have the same type",
              text,
              ctx
          )
          te.wdlType
        } else if (ve.wdlType != T_Boolean) {
          val msg =
            s"condition ${exprToString(ve)} should have boolean type, it has type ${typeToString(ve.wdlType)} instead"
          handleError(msg, expr.text, ctx)
          ve.wdlType
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
            handleError(msg, expr.text, ctx)
            ve.wdlType
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
            handleError(msg, ve.text, ctx)
            other
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
            handleError(s"${exprToString(eIndex)} must be an integer", text, ctx)
            eIndex.wdlType
          case (_, _) =>
            handleError(s"expression ${exprToString(eArray)} must be an array", eArray.text, ctx)
            eArray.wdlType
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
            handleError(s"condition ${exprToString(eCond)} must be a boolean", eCond.text, ctx)
            eCond.wdlType
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
                handleError(msg, expr.text, ctx)
                eTrueBranch.wdlType
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
          case e: StdlibFunctionException =>
            handleError(e.getMessage, expr.text, ctx)
            TAT.ValueNone(T_Any, expr.text)
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
            handleError(s"struct ${id} has not been defined", text, ctx)
            T_Any
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
          handleError(msg, decl.text, ctx)
          lhsType
        }
        TAT.Declaration(decl.name, wdlType, Some(e), decl.text)
    }

    // There are cases where we want to allow shadowing. For example, it
    // is legal to have an output variable with the same name as an input variable.
    if (!canShadow && ctx.lookup(decl.name, bindings).isDefined) {
      handleError(s"variable ${decl.name} shadows an existing variable", decl.text, ctx)
    }

    tDecl
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
            if (names.contains(decl.name)) {
              handleError(s"Input section has duplicate definition ${decl.name}",
                          inputSection.text,
                          ctx)
            }
            val tDecl = applyDecl(decl, bindings, ctx)
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
            if (names.contains(decl.name)) {
              handleError(s"Output section has duplicate definition ${decl.name}", decl.text, ctx)
            }
            if (both.contains(decl.name)) {
              handleError(s"Definition ${decl.name} shadows exisiting declarations", decl.text, ctx)
            }
            val tDecl = applyDecl(decl, bindings, ctx, canShadow = true)
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
        handleError(s"${SUtil.exprToString(other)} is an invalid meta value", expr.text, ctx)
        TAT.MetaNull(other.text)
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
            handleError(
                s"parameter_meta key ${kv.id} does not refer to an input or output declaration",
                kv.text,
                ctx
            )
            TAT.MetaNull(kv.expr.text)
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
        val newCtx =
          try {
            curCtx.bindVar(tDecl.name, tDecl.wdlType)
          } catch {
            case e: DuplicateDeclarationException =>
              handleError(e.getMessage, tDecl.text, ctx)
              curCtx
          }
        (tDecls :+ tDecl, newCtx)
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
          handleError(
              s"Expression ${exprToString(e)} in the command section is not coercible to a string",
              expr.text,
              ctx
          )
          TAT.ValueString(exprToString(e), T_String, e.text)
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
        wdlType = T_Task(task.name, inputType, outputType),
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
        handleError(s"called task/workflow ${call.name} is not defined", call.text, ctx)
        WdlTypes.T_Task(call.name, Map.empty, Map.empty)
      case Some(x: T_Callable) => x
    }

    // check if the call shadows an existing call
    val callType = T_Call(actualName, callee.output)
    val wdlType = if (ctx.declarations contains actualName) {
      handleError(s"call ${actualName} shadows an existing definition", call.text, ctx)
      T_Call(actualName, Map.empty)
    } else {
      callType
    }

    // check that any afters refer to valid calls
    val afters = call.afters.map { after =>
      ctx.declarations.get(after.name) match {
        case Some(c: T_Call) => c
        case Some(_) =>
          handleError(
              s"call ${actualName} after clause refers to non-call declaration ${after.name}",
              after.text,
              ctx
          )
          T_Call(after.name, Map.empty)
        case None =>
          handleError(s"call ${actualName} after clause refers to non-existant call ${after.name}",
                      after.text,
                      ctx)
          T_Call(after.name, Map.empty)
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
          if (errorMsg.nonEmpty) {
            handleError(errorMsg.get, call.text, ctx)
          }
          argName -> tExpr
        }.toMap
      case None => Map.empty
    }

    // check that all the compulsory arguments are provided
    val missingInputs = callee.input.flatMap {
      case (argName, (_, false)) =>
        callerInputs.get(argName) match {
          case None =>
            handleError(s"compulsory argument ${argName} to task/workflow ${call.name} is missing",
                        call.text,
                        ctx)
            Some(argName -> TAT.ValueNone(T_Any, call.text))
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

      case ((bindings, wElements), wElt) =>
        val newCtx =
          try {
            ctx.bindVarList(bindings)
          } catch {
            case e: DuplicateDeclarationException =>
              handleError(e.getMessage, wElt.text, ctx)
              ctx
          }
        wElt match {
          case call: AST.Call =>
            val tCall = applyCall(call, newCtx)
            (bindings + (tCall.actualName -> tCall.wdlType), wElements :+ tCall)

          case subSct: AST.Scatter =>
            // a nested scatter
            val (tScatter, sctBindings) = applyScatter(subSct, newCtx)
            (bindings ++ sctBindings, wElements :+ tScatter)

          case cond: AST.Conditional =>
            // a nested conditional
            val (tCond, condBindings) = applyConditional(cond, newCtx)
            (bindings ++ condBindings, wElements :+ tCond)

          case other => throw new RuntimeException(s"Unexpected workflow element ${other}")
        }
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
        handleError(s"Collection in scatter (${scatter}) is not an array type",
                    scatter.text,
                    ctxOuter)
        other
    }
    // add a binding for the iteration variable
    //
    // The iterator identifier is not exported outside the scatter
    val (ctxInner, wdlType) =
      try {
        (ctxOuter.bindVar(scatter.identifier, elementType), elementType)
      } catch {
        case e: DuplicateDeclarationException =>
          handleError(e.getMessage, scatter.text, ctxOuter)
          (ctxOuter, elementType)
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
          callName -> T_Call(callType.name, callOutput)
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
        handleError(s"Expression ${exprToString(e)} must have boolean type", cond.text, ctxOuter)
        TAT.ValueBoolean(value = false, T_Boolean, e.text)
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
          callName -> T_Call(callType.name, callOutput)
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
        wdlType = T_Workflow(wf.name, inputType, outputType),
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
          val newCtx =
            try {
              ctx.bindCallable(task2.wdlType)
            } catch {
              case e: DuplicateDeclarationException =>
                handleError(e.getMessage, task.text, ctx)
                ctx
            }
          (newCtx, elems :+ task2)

        case ((ctx, elems), iStat: AST.ImportDoc) =>
          // recurse into the imported document, add types
          val (iDoc, iCtx) = applyDoc(iStat.doc.get)
          val name = iStat.name.map(_.value)
          val addr = iStat.addr.value

          // Figure out what to name the sub-document
          val namespace = name match {
            case None =>
              // Something like
              //    import "http://example.com/lib/stdlib"
              //    import "A/B/C"
              // Where the user does not specify an alias. The namespace
              // will be named:
              //    stdlib
              //    C
              UUtil.getFilename(addr, ".wdl")
            case Some(x) => x
          }

          val aliases = iStat.aliases.map {
            case AST.ImportAlias(id1, id2, text) =>
              TAT.ImportAlias(id1, id2, ctx.aliases(id1), text)
          }

          val importDoc = TAT.ImportDoc(namespace, aliases, addr, iDoc, iStat.text)

          // add the externally visible definitions to the context
          val newCtx =
            try {
              ctx.bindImportedDoc(namespace, iCtx, iStat.aliases)
            } catch {
              case e: DuplicateDeclarationException =>
                handleError(e.getMessage, importDoc.text, ctx)
                ctx
            }
          (newCtx, elems :+ importDoc)

        case ((ctx, elems), struct: AST.TypeStruct) =>
          // Add the struct to the context
          val tStruct = typeFromAst(struct, struct.text, ctx).asInstanceOf[T_Struct]
          val defStruct = TAT.StructDefinition(struct.name, tStruct, tStruct.members, struct.text)
          val newCtx =
            try {
              ctx.bindStruct(tStruct)
            } catch {
              case e: DuplicateDeclarationException =>
                handleError(e.getMessage, struct.text, ctx)
                ctx
            }
          (newCtx, elems :+ defStruct)
      }

    // now that we have types for everything else, we can check the workflow
    val (tWf, contextFinal) = doc.workflow match {
      case None => (None, context)
      case Some(wf) =>
        val tWf = applyWorkflow(wf, context)
        val ctxFinal =
          try {
            context.bindCallable(tWf.wdlType)
          } catch {
            case e: DuplicateDeclarationException =>
              handleError(e.getMessage, wf.text, context)
              context
          }
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
