package wdlTools.types

import java.nio.file.Paths

import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.Util.exprToString
import wdlTools.syntax.TextSource
import wdlTools.util.TypeCheckingRegime._
import wdlTools.util.{Util => UUtil}
import wdlTools.types.WdlTypes._
import wdlTools.types.{Util => TUtil}
import wdlTools.types.{TypedAbstractSyntax => TAT}

case class TypeEval(stdlib: Stdlib) {
  private val tUtil = TUtil(stdlib.conf)
  private val regime = stdlib.conf.typeChecking

  // A group of bindings. This is typically a part of the context. For example,
  // the body of a scatter.
  type WdlType = WdlTypes.T
  type Bindings = Map[String, WdlType]

  private def typeEvalMathOp(expr: AST.Expr, ctx: Context): WdlType = {
    val t = typeEval(expr, ctx)
    t match {
      case _: AST.TypeInt   => T_Int
      case _: AST.TypeFloat => T_Float
      case _ =>
        throw new TypeException(s"${exprToString(expr)} must be an integer or a float",
                                expr.text,
                                ctx.docSourceUrl)
    }
  }

  // The add operation is overloaded.
  // 1) The result of adding two integers is an integer
  // 2)    -"-                   floats   -"-   float
  // 3)    -"-                   strings  -"-   string
  private def typeEvalAdd(a: AST.Expr, b: AST.Expr, ctx: Context): WdlType = {
    def isPseudoString(x: T): Boolean = {
      x match {
        case T_String             => true
        case T_Optional(T_String) => true
        case T_File               => true
        case T_Optional(T_File)   => true
        case _                    => false
      }
    }
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
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

      // adding files is equivalent to concatenating paths
      case (T_File, T_String | T_File) => T_File

      case (_, _) =>
        throw new TypeException(
            s"Expressions ${exprToString(a)} and ${exprToString(b)} cannot be added",
            a.text,
            ctx.docSourceUrl
        )
    }
  }

  private def typeEvalMathOp(a: AST.Expr, b: AST.Expr, ctx: Context): WdlType = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (T_Int, T_Int)     => T_Int
      case (T_Float, T_Int)   => T_Float
      case (T_Int, T_Float)   => T_Float
      case (T_Float, T_Float) => T_Float
      case (_, _) =>
        throw new TypeException(
            s"Expressions ${exprToString(a)} and ${exprToString(b)} must be integers or floats",
            a.text,
            ctx.docSourceUrl
        )
    }
  }

  private def typeEvalLogicalOp(expr: AST.Expr, ctx: Context): WdlType = {
    val t = typeEval(expr, ctx)
    t match {
      case T_Boolean => T_Boolean
      case other =>
        throw new TypeException(
            s"${exprToString(expr)} must be a boolean, it is ${tUtil.toString(other)}",
            expr.text,
            ctx.docSourceUrl
        )
    }
  }

  private def typeEvalLogicalOp(a: AST.Expr, b: AST.Expr, ctx: Context): WdlType = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (T_Boolean, T_Boolean) => T_Boolean
      case (_, _) =>
        throw new TypeException(s"${exprToString(a)} and ${exprToString(b)} must have boolean type",
                                a.text,
                                ctx.docSourceUrl)
    }
  }

  private def typeEvalCompareOp(a: AST.Expr, b: AST.Expr, ctx: Context): WdlType = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    if (at == bt) {
      // These could be complex types, such as Array[Array[Int]].
      return T_Boolean
    }

    // Even if the types are not the same, there are cases where they can
    // be compared.
    (at, bt) match {
      case (T_Int, T_Float) => T_Boolean
      case (T_Float, T_Int) => T_Boolean
      case (_, _) =>
        throw new TypeException(
            s"Expressions ${exprToString(a)} and ${exprToString(b)} must have the same type",
            a.text,
            ctx.docSourceUrl
        )
    }
  }

  private def typeFromAst(t: AST.Type, text: TextSource, ctx: Context): WdlType = {
    t match {
      case AST.TypeOptional(t, _) => T_Optional(typeFromAst(t, text, ctx))
      case AST.TypeArray(t, _, _) => T_Array(typeFromAst(t, text, ctx))
      case AST.TypeMap(k, v, _)   => T_Map(typeFromAst(k, text, ctx), typeFromAst(v, text, ctx))
      case AST.TypePair(l, r, _)  => T_Pair(typeFromAst(l, text, ctx), typeFromAst(r, text, ctx))
      case _: AST.TypeString      => T_String
      case _: AST.TypeFile        => T_File
      case _: AST.TypeBoolean     => T_Boolean
      case _: AST.TypeInt         => T_Int
      case _: AST.TypeFloat       => T_Float
      case AST.TypeIdentifier(id, _) =>
        ctx.structs.get(id) match {
          case None =>
            throw new TypeException(s"struct ${id} has not been defined", text, ctx.docSourceUrl)
          case Some(struct) => struct
        }
      case _: AST.TypeObject => T_Object
      case AST.TypeStruct(name, members, _) =>
        T_Struct(name, members.map {
          case AST.StructMember(name, t2, _) => name -> typeFromAst(t2, text, ctx)
        }.toMap)
    }
  }

  // Figure out what the type of an expression is.
  //
  private def typeEval(expr: AST.Expr, ctx: Context): WdlType = {
    expr match {
      // base cases, primitive types
      case _: AST.ValueString  => T_String
      case _: AST.ValueFile    => T_File
      case _: AST.ValueBoolean => T_Boolean
      case _: AST.ValueInt     => T_Int
      case _: AST.ValueFloat   => T_Float

      // an identifier has to be bound to a known type
      case AST.ExprIdentifier(id, _) =>
        ctx.declarations.get(id) match {
          case None =>
            throw new TypeException(s"Identifier ${id} is not defined", expr.text, ctx.docSourceUrl)
          case Some(t) => t
        }

      // All the sub-exressions have to be strings, or coercible to strings
      case AST.ExprCompoundString(vec, _) =>
        vec foreach { subExpr =>
          val t = typeEval(subExpr, ctx)
          if (!tUtil.isCoercibleTo(T_String, t))
            throw new TypeException(
                s"expression ${exprToString(expr)} of type ${t} is not coercible to string",
                expr.text,
                ctx.docSourceUrl
            )
        }
        T_String

      case AST.ExprPair(l, r, _)                => T_Pair(typeEval(l, ctx), typeEval(r, ctx))
      case AST.ExprArray(vec, _) if vec.isEmpty =>
        // The array is empty, we can't tell what the array type is.
        // TODO: replace the Any type with a type-parameter
        T_Array(T_Any)

      case AST.ExprArray(vec, _) =>
        val vecTypes = vec.map(typeEval(_, ctx))
        val (t, _) =
          try {
            tUtil.unifyCollection(vecTypes, Map.empty)
          } catch {
            case _: TypeUnificationException =>
              throw new TypeException(
                  "array elements must have the same type, or be coercible to one",
                  expr.text,
                  ctx.docSourceUrl
              )
          }
        T_Array(t)

      case _: AST.ExprObject =>
        T_Object

      case AST.ExprMap(m, _) if m.isEmpty =>
        // The map type is unknown.
        // TODO: replace the Any type with a type-parameter
        T_Map(T_Any, T_Any)

      case AST.ExprMap(m, _) =>
        // figure out the types from the first element
        val mTypes: Map[T, T] = m.map { item: AST.ExprMapItem =>
          typeEval(item.key, ctx) -> typeEval(item.value, ctx)
        }.toMap
        val (tk, _) =
          try {
            tUtil.unifyCollection(mTypes.keys, Map.empty)
          } catch {
            case _: TypeUnificationException =>
              throw new TypeException("map keys must have the same type, or be coercible to one",
                                      expr.text,
                                      ctx.docSourceUrl)
          }
        val (tv, _) =
          try {
            tUtil.unifyCollection(mTypes.values, Map.empty)
          } catch {
            case _: TypeUnificationException =>
              throw new TypeException("map values must have the same type, or be coercible to one",
                                      expr.text,
                                      ctx.docSourceUrl)
          }
        T_Map(tk, tv)

      // These are expressions like:
      // ${true="--yes" false="--no" boolean_value}
      case AST.ExprPlaceholderEqual(t: AST.Expr, f: AST.Expr, value: AST.Expr, _) =>
        val tType = typeEval(t, ctx)
        val fType = typeEval(f, ctx)
        if (fType != tType)
          throw new TypeException(s"""|subexpressions ${exprToString(t)} and ${exprToString(f)}
                                      |in ${exprToString(expr)} must have the same type""".stripMargin
                                    .replaceAll("\n", " "),
                                  expr.text,
                                  ctx.docSourceUrl)
        val tv = typeEval(value, ctx)
        if (tv != T_Boolean)
          throw new TypeException(
              s"${value} in ${exprToString(expr)} should have boolean type, it has type ${tUtil.toString(tv)} instead",
              expr.text,
              ctx.docSourceUrl
          )
        tType

      // An expression like:
      // ${default="foo" optional_value}
      case AST.ExprPlaceholderDefault(default: AST.Expr, value: AST.Expr, _) =>
        val vt = typeEval(value, ctx)
        val dt = typeEval(default, ctx)
        vt match {
          case T_Optional(vt2) if tUtil.isCoercibleTo(dt, vt2) => dt
          case vt2 if tUtil.isCoercibleTo(dt, vt2)             =>
            // another unsavory case. The optional_value is NOT optional.
            dt
          case _ =>
            throw new TypeException(
                s"""|Expression (${exprToString(value)}) must have type coercible to
                    |(${tUtil.toString(dt)}), it has type (${vt}) instead
                    |""".stripMargin.replaceAll("\n", " "),
                expr.text,
                ctx.docSourceUrl
            )
        }

      // An expression like:
      // ${sep=", " array_value}
      case AST.ExprPlaceholderSep(sep: AST.Expr, value: AST.Expr, _) =>
        val sepType = typeEval(sep, ctx)
        if (sepType != T_String)
          throw new TypeException(s"separator ${sep} in ${expr} must have string type",
                                  expr.text,
                                  ctx.docSourceUrl)
        val vt = typeEval(value, ctx)
        vt match {
          case T_Array(x, _) if tUtil.isCoercibleTo(T_String, x) =>
            T_String
          case other =>
            throw new TypeException(
                s"expression ${value} should be coercible to Array[String], but it is ${other}",
                expr.text,
                ctx.docSourceUrl
            )
        }

      // math operators on one argument
      case AST.ExprUniraryPlus(value, _)  => typeEvalMathOp(value, ctx)
      case AST.ExprUniraryMinus(value, _) => typeEvalMathOp(value, ctx)

      // logical operators
      case AST.ExprLor(a: AST.Expr, b: AST.Expr, _)  => typeEvalLogicalOp(a, b, ctx)
      case AST.ExprLand(a: AST.Expr, b: AST.Expr, _) => typeEvalLogicalOp(a, b, ctx)
      case AST.ExprNegate(value: AST.Expr, _)        => typeEvalLogicalOp(value, ctx)

      // equality comparisons
      case AST.ExprEqeq(a: AST.Expr, b: AST.Expr, _) => typeEvalCompareOp(a, b, ctx)
      case AST.ExprNeq(a: AST.Expr, b: AST.Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case AST.ExprLt(a: AST.Expr, b: AST.Expr, _)   => typeEvalCompareOp(a, b, ctx)
      case AST.ExprGte(a: AST.Expr, b: AST.Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case AST.ExprLte(a: AST.Expr, b: AST.Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case AST.ExprGt(a: AST.Expr, b: AST.Expr, _)   => typeEvalCompareOp(a, b, ctx)

      // add is overloaded, it is a special case
      case AST.ExprAdd(a: AST.Expr, b: AST.Expr, _) => typeEvalAdd(a, b, ctx)

      // math operators on two arguments
      case AST.ExprSub(a: AST.Expr, b: AST.Expr, _)    => typeEvalMathOp(a, b, ctx)
      case AST.ExprMod(a: AST.Expr, b: AST.Expr, _)    => typeEvalMathOp(a, b, ctx)
      case AST.ExprMul(a: AST.Expr, b: AST.Expr, _)    => typeEvalMathOp(a, b, ctx)
      case AST.ExprDivide(a: AST.Expr, b: AST.Expr, _) => typeEvalMathOp(a, b, ctx)

      // Access an array element at [index]
      case AST.ExprAt(array: AST.Expr, index: AST.Expr, _) =>
        val idxt = typeEval(index, ctx)
        if (idxt != T_Int)
          throw new TypeException(s"${index} must be an integer", expr.text, ctx.docSourceUrl)
        val arrayt = typeEval(array, ctx)
        arrayt match {
          case T_Array(elemType, _) => elemType
          case _ =>
            throw new TypeException(s"subexpression ${array} in (${expr}) must be an array",
                                    expr.text,
                                    ctx.docSourceUrl)
        }

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case AST.ExprIfThenElse(cond: AST.Expr, tBranch: AST.Expr, fBranch: AST.Expr, _) =>
        val condType = typeEval(cond, ctx)
        if (condType != T_Boolean)
          throw new TypeException(s"condition ${exprToString(cond)} must be a boolean",
                                  expr.text,
                                  ctx.docSourceUrl)
        val tBranchT = typeEval(tBranch, ctx)
        val fBranchT = typeEval(fBranch, ctx)
        try {
          val (t, _) = tUtil.unify(tBranchT, fBranchT, Map.empty)
          t
        } catch {
          case _: TypeUnificationException =>
            throw new TypeException(
                s"""|The branches of a conditional expression must be coercable to the same type
                    |expression: ${exprToString(expr)}
                    |  true branch: ${tUtil.toString(tBranchT)}
                    |  flase branch: ${tUtil.toString(fBranchT)}
                    |""".stripMargin,
                expr.text,
                ctx.docSourceUrl
            )
        }

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case AST.ExprApply(funcName: String, elements: Vector[AST.Expr], _) =>
        val elementTypes = elements.map(typeEval(_, ctx))
        stdlib.apply(funcName, elementTypes, expr)

      // Access a field in a struct or an object. For example "x.a" in:
      //   Int z = x.a
      case AST.ExprGetName(e: AST.Expr, id: String, _) =>
        val et = typeEval(e, ctx)
        et match {
          case T_Struct(name, members) =>
            members.get(id) match {
              case None =>
                throw new TypeException(
                    s"Struct ${name} does not have member ${id} in expression",
                    expr.text,
                    ctx.docSourceUrl
                )
              case Some(t) =>
                t
            }

          case T_Call(name, members) =>
            members.get(id) match {
              case None =>
                throw new TypeException(
                    s"Call object ${name} does not have member ${id} in expression",
                    expr.text,
                    ctx.docSourceUrl
                )
              case Some(t) =>
                t
            }

          // An identifier is a struct, and we want to access
          // a field in it.
          // Person p = census.p
          // String name = p.name
          case T_Identifier(structName) =>
            // produce the struct definition
            val members = ctx.structs.get(structName) match {
              case None =>
                throw new TypeException(s"unknown struct ${structName}",
                                        expr.text,
                                        ctx.docSourceUrl)
              case Some(T_Struct(_, members)) => members
              case other =>
                throw new TypeException(s"not a struct ${other}", expr.text, ctx.docSourceUrl)
            }
            members.get(id) match {
              case None =>
                throw new TypeException(s"Struct ${structName} does not have member ${id}",
                                        expr.text,
                                        ctx.docSourceUrl)
              case Some(t) => t
            }

          // accessing a pair element
          case T_Pair(l, _) if id.toLowerCase() == "left"  => l
          case T_Pair(_, r) if id.toLowerCase() == "right" => r
          case T_Pair(_, _) =>
            throw new TypeException(s"accessing a pair with (${id}) is illegal",
                                    expr.text,
                                    ctx.docSourceUrl)

          case _ =>
            throw new TypeException(s"member access (${id}) in expression is illegal",
                                    expr.text,
                                    ctx.docSourceUrl)
        }
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
  private def applyDecl(decl: AST.Declaration, ctx: Context): (String, T) = {
    val lhsType: WdlType = typeFromAst(decl.wdlType, decl.text, ctx)
    (lhsType, decl.expr) match {
      // Int x
      case (_, None) =>
        ()

      case (_, Some(expr)) =>
        val rhsType = typeEval(expr, ctx)
        if (!tUtil.isCoercibleTo(lhsType, rhsType)) {
          throw new TypeException(s"""|${decl.name} is of type ${tUtil.toString(lhsType)}
                                      |but is assigned ${tUtil.toString(rhsType)}
                                      |${expr}
                                      |""".stripMargin.replaceAll("\n", " "),
                                  decl.text,
                                  ctx.docSourceUrl)
        }
    }
    (decl.name, lhsType)
  }

  // type check the input section and return bindings for all of the input variables.
  private def applyInputSection(inputSection: AST.InputSection, ctx: Context): Bindings = {
    inputSection.declarations.foldLeft(Map.empty[String, T]) {
      case (accu, decl) =>
        val (varName, typ) = applyDecl(decl, ctx.bindVarList(accu, inputSection.text))
        accu + (varName -> typ)
    }
  }

  // type check the input section and return bindings for all of the output variables.
  private def applyOutputSection(outputSection: AST.OutputSection, ctx: Context): Bindings = {
    outputSection.declarations.foldLeft(Map.empty[String, T]) {
      case (accu, decl) =>
        // check the declaration and add a binding for its (variable -> wdlType)
        val (varName, typ) = applyDecl(decl, ctx.bindVarList(accu, outputSection.text))
        accu + (varName -> typ)
    }
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(inputSection: Option[AST.InputSection],
                            outputSection: Option[AST.OutputSection],
                            ctx: Context): (Map[String, (T, Boolean)], Map[String, T]) = {

    val inputType: Map[String, (T, Boolean)] = inputSection match {
      case None => Map.empty
      case Some(AST.InputSection(decls, _)) =>
        decls.map {
          case AST.Declaration(name, wdlType, Some(_), text) =>
            // input has a default value, caller may omit it.
            val t = typeFromAst(wdlType, text, ctx)
            name -> (t, true)

          case AST.Declaration(name, AST.TypeOptional(wdlType, _), _, text) =>
            // input is optional, caller can omit it.
            val t = typeFromAst(wdlType, text, ctx)
            name -> (T_Optional(t), true)

          case AST.Declaration(name, wdlType, _, text) =>
            // input is compulsory
            val t = typeFromAst(wdlType, text, ctx)
            name -> (t, false)
        }.toMap
    }
    val outputType: Map[String, T] = outputSection match {
      case None => Map.empty
      case Some(AST.OutputSection(decls, _)) =>
        decls.map(decl => decl.name -> typeFromAst(decl.wdlType, decl.text, ctx)).toMap
    }
    (inputType, outputType)
  }

  // The runtime section can make use of values defined in declarations
  private def applyRuntime(rtSection: AST.RuntimeSection, ctx: Context): Unit = {
    rtSection.kvs.foreach {
      case AST.RuntimeKV(_, expr, _) =>
        val _ = typeEval(expr, ctx)
    }
  }

  // TASK
  //
  // - An inputs type has to match the type of its default value (if any)
  // - Check the declarations
  // - Assignments to an output variable must match
  //
  // We can't check the validity of the command section.
  private def applyTask(task: AST.Task, ctxOuter: Context): (TAT.Task, T_Task) = {
    val ctx: Context = task.input match {
      case None => ctxOuter
      case Some(inpSection) =>
        val bindings = applyInputSection(inpSection, ctxOuter)
        ctxOuter.bindVarList(bindings, task.text)
    }

    // check the declarations, and accumulate context
    val ctxDecl = task.declarations.foldLeft(ctx) {
      case (accu: Context, decl) =>
        val (varName, typ) = applyDecl(decl, accu)
        accu.bindVar(varName, typ, decl.text)
    }

    // check the runtime section
    task.runtime.foreach(rtSection => applyRuntime(rtSection, ctxDecl))

    // check that all expressions can be coereced to a string inside
    // the command section
    task.command.parts.foreach { expr =>
      val t = typeEval(expr, ctxDecl)
      val valid = t match {
        case x if tUtil.isPrimitive(x)             => true
        case T_Optional(x) if tUtil.isPrimitive(x) => true
        case _                                     => false
      }
      if (!valid)
        throw new TypeException(
            s"Expression ${exprToString(expr)} in the command section is not coercible to a string",
            expr.text,
            ctx.docSourceUrl
        )
    }

    // check the output section. We don't need the returned context.
    task.output.map(x => applyOutputSection(x, ctxDecl))

    // calculate the type signature of the task
    val (inputType, outputType) = calcSignature(task.input, task.output, ctxOuter)
    T_Task(task.name, inputType, outputType)
  }

  //
  // 1. all the caller arguments have to exist with the correct types
  //    in the callee
  // 2. all the compulsory callee arguments must be specified. Optionals
  //    and arguments that have defaults can be skipped.
  private def applyCall(call: AST.Call, ctx: Context): (String, T_Call) = {
    val callerInputs: Map[String, T] = call.inputs match {
      case Some(AST.CallInputs(value, _)) =>
        value.map { inp =>
          inp.name -> typeEval(inp.expr, ctx)
        }.toMap
      case None => Map.empty
    }

    val (calleeInputs, calleeOutputs) = ctx.callables.get(call.name) match {
      case None =>
        throw new TypeException(s"called task/workflow ${call.name} is not defined",
                                call.text,
                                ctx.docSourceUrl)
      case Some(T_Task(_, input, output)) =>
        (input, output)
      case Some(T_Workflow(_, input, output)) =>
        (input, output)
      case _ =>
        throw new TypeException(s"callee ${call.name} is not a task or workflow",
                                call.text,
                                ctx.docSourceUrl)
    }

    // type-check input arguments
    callerInputs.foreach {
      case (argName, wdlType) =>
        calleeInputs.get(argName) match {
          case None =>
            throw new TypeException(
                s"call ${call} has argument ${argName} that does not exist in the callee",
                call.text,
                ctx.docSourceUrl
            )
          case Some((calleeType, _)) if regime == Strict =>
            if (calleeType != wdlType)
              throw new TypeException(
                  s"argument ${argName} has wrong type ${wdlType}, expecting ${calleeType}",
                  call.text,
                  ctx.docSourceUrl
              )
          case Some((calleeType, _)) if regime >= Moderate =>
            if (!tUtil.isCoercibleTo(calleeType, wdlType))
              throw new TypeException(
                  s"argument ${argName} has type ${wdlType}, it is not coercible to ${calleeType}",
                  call.text,
                  ctx.docSourceUrl
              )
          case _ => ()
        }
    }

    // check that all the compulsory arguments are provided
    calleeInputs.foreach {
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
    val callName = call.alias match {
      case None if !(call.name contains ".") =>
        call.name
      case None =>
        val parts = call.name.split("\\.")
        parts.last
      case Some(alias) => alias.name
    }

    if (ctx.declarations contains callName)
      throw new TypeException(s"call ${callName} shadows an existing definition",
                              call.text,
                              ctx.docSourceUrl)

    // build a type for the resulting object
    callName -> T_Call(callName, calleeOutputs)
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
  private def applyScatter(scatter: AST.Scatter, ctxOuter: Context): Bindings = {
    val collectionType = typeEval(scatter.expr, ctxOuter)
    val elementType = collectionType match {
      case T_Array(elementType, _) => elementType
      case _ =>
        throw new Exception(s"Collection in scatter (${scatter}) is not an array type")
    }
    // add a binding for the iteration variable
    val ctxInner = ctxOuter.bindVar(scatter.identifier, elementType, scatter.text)

    // Add an array type to all variables defined in the scatter body
    val bodyBindings: Bindings = scatter.body.foldLeft(Map.empty[String, T]) {
      case (accu: Bindings, decl: AST.Declaration) =>
        val (varName, typ) = applyDecl(decl, ctxInner.bindVarList(accu, decl.text))
        accu + (varName -> typ)

      case (accu: Bindings, call: AST.Call) =>
        val (callName, callType) = applyCall(call, ctxInner.bindVarList(accu, call.text))
        accu + (callName -> callType)

      case (accu: Bindings, subSct: AST.Scatter) =>
        // a nested scatter
        val sctBindings = applyScatter(subSct, ctxInner.bindVarList(accu, subSct.text))
        accu ++ sctBindings

      case (accu: Bindings, cond: AST.Conditional) =>
        // a nested conditional
        val condBindings = applyConditional(cond, ctxInner.bindVarList(accu, cond.text))
        accu ++ condBindings

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }

    // The iterator identifier is not exported outside the scatter
    bodyBindings.map {
      case (callName, callType: T_Call) =>
        val callOutput = callType.output.map {
          case (name, t) => name -> T_Array(t)
        }
        callName -> T_Call(callType.name, callOutput)
      case (varName, typ: T) =>
        varName -> T_Array(typ)
    }
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
  private def applyConditional(cond: AST.Conditional, ctxOuter: Context): Bindings = {
    val condType = typeEval(cond.expr, ctxOuter)
    if (condType != T_Boolean)
      throw new Exception(s"Expression ${cond.expr} must have boolean type")

    // keep track of the inner/outer bindings. Within the block we need [inner],
    // [outer] is what we produce, which has the optional modifier applied to
    // everything.
    val bodyBindings = cond.body.foldLeft(Map.empty[String, T]) {
      case (accu: Bindings, decl: AST.Declaration) =>
        val (varName, typ) = applyDecl(decl, ctxOuter.bindVarList(accu, decl.text))
        accu + (varName -> typ)

      case (accu: Bindings, call: AST.Call) =>
        val (callName, callType) = applyCall(call, ctxOuter.bindVarList(accu, call.text))
        accu + (callName -> callType)

      case (accu: Bindings, subSct: AST.Scatter) =>
        // a nested scatter
        val sctBindings = applyScatter(subSct, ctxOuter.bindVarList(accu, subSct.text))
        accu ++ sctBindings

      case (accu: Bindings, cond: AST.Conditional) =>
        // a nested conditional
        val condBindings = applyConditional(cond, ctxOuter.bindVarList(accu, cond.text))
        accu ++ condBindings

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }

    bodyBindings.map {
      case (callName, callType: T_Call) =>
        val callOutput = callType.output.map {
          case (name, t) => name -> makeOptional(t)
        }
        callName -> T_Call(callType.name, callOutput)
      case (varName, typ: WdlType) =>
        varName -> makeOptional(typ)
    }
  }

  private def applyWorkflow(wf: AST.Workflow, ctxOuter: Context): (TAT.Document, Context) = {
    val ctx: Context = wf.input match {
      case None => ctxOuter
      case Some(inpSection) =>
        val inputs = applyInputSection(inpSection, ctxOuter)
        ctxOuter.bindVarList(inputs, inpSection.text)
    }

    val ctxBody = wf.body.foldLeft(ctx) {
      case (accu: Context, decl: AST.Declaration) =>
        val (name, typ) = applyDecl(decl, accu)
        accu.bindVar(name, typ, decl.text)

      case (accu: Context, call: AST.Call) =>
        val (callName, callType) = applyCall(call, accu)
        accu.bindVar(callName, callType, call.text)

      case (accu: Context, scatter: AST.Scatter) =>
        val sctBindings = applyScatter(scatter, accu)
        accu.bindVarList(sctBindings, scatter.text)

      case (accu: Context, cond: AST.Conditional) =>
        val condBindings = applyConditional(cond, accu)
        accu.bindVarList(condBindings, cond.text)

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }

    // check the output section. We don't need the returned context.
    wf.output.map(x => applyOutputSection(x, ctxBody))

    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(wf.input, wf.output, ctxOuter)
    val wfSignature = T_Workflow(wf.name, inputType, outputType)
    val ctxFinal = ctxOuter.bindCallable(wfSignature, wf.text)
    ctxFinal
  }

  private def apply2(doc: AST.Document): (TAT.Document, Context) = {
    val initCtx = Context(docSourceUrl = Some(doc.docSourceUrl))

    val (context, elements) =
      doc.elements.foldLeft((initCtx, Vector.empty[TAT.WorkflowElement])) {
        case (ctx, elems, task: AST.Task) =>
          val (task2, taskSig) = applyTask(task, ctx)
          (ctx.bindCallable(taskSig, task.text),
           elems :+ task2)

        case (ctx, elems, iStat: AST.ImportDoc) =>
          // recurse into the imported document, add types
          val (itDoc, iCtx) = apply2(iStat.doc.get)

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
              val url = UUtil.getUrl(iStat.addr.value, stdlib.conf.localDirectories)
              val nsName = Paths.get(url.getFile).getFileName.toString
              if (nsName.endsWith(".wdl"))
                nsName.dropRight(".wdl".length)
              else
                nsName
            case Some(x) => x.value
          }

          val importDoc = TAT.ImportDoc(iStat.name,
                                        iStat.aliases.map( ),
                                        iStat.add.value,
                                        iDoc,
                                        iStat.text)

          // add the externally visible definitions to the context
          (ctx.bindImportedDoc(namespace, iCtx, iStat.aliases, iStat.text),
           elems :+ importDoc)

        case (ctx, tDoc, struct: AST.TypeStruct) =>
          // Add the struct to the context
          val t = typeFromAst(struct, struct.text, accu)
          val t2 = t.asInstanceOf[T_Struct]
          accu.bind(t2, struct.text)

        case (_, _, other) =>
          throw new Exception(s"sanity: wrong element type in workflow $other")
      }

    // now that we have types for everything else, we can check the workflow
    val wf = doc.workflow match {
      case None     => None
      case Some(wf) => Some(applyWorkflow(wf, context))
    }

    TAT.Document(doc.docSourceUrl,
                 doc.sourceCode,
                 TAT.Version(doc.version.value, doc.version.text),
                 elements,
                 wf,
                 doc.text,
                 doc.comments)
  }

  // Main entry point
  //
  // check if the WDL document is correctly typed. Otherwise, throw an exception
  // describing the problem in a human readable fashion. Return a document
  // with types.
  //
  def apply(doc: AST.Document): TAT.Document = {
    val (tDoc, _) = apply2(doc)
    taxDoc
  }
}
