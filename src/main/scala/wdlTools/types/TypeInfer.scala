package wdlTools.types

import wdlTools.syntax.{
  Operator,
  SourceLocation,
  WdlVersion,
  AbstractSyntax => AST,
  SyntaxUtils => SUtil
}
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.types.WdlTypes._
import wdlTools.types.TypeUtils.{isPrimitive, prettyFormatExpr, prettyFormatType}
import TypeCheckingRegime._
import wdlTools.types.ExprState.ExprState
import wdlTools.types.Section.Section
import dx.util.{
  AddressableFileSource,
  Bindings,
  DuplicateBindingException,
  FileNode,
  FileSourceResolver,
  Logger,
  TraceLevel,
  FileUtils => UUtil
}

import scala.collection.immutable.{SeqMap, TreeSeqMap}

trait TypeErrorHandler {
  def handleTypeErrors(errors: Vector[TypeError]): Boolean

  def hasTypeErrors: Boolean
}

/**
  * Type inference
  * @param regime Type checking rules. Are we lenient or strict in checking coercions?
  * @param allowNonWorkflowInputs whether task inputs can be specified externally
  * @param userDefinedFunctions non-stdlib functions to support
  * @param substituteFunctionsForTasks whether to allow UDFs to be substituted for matching tasks
  * @param errorHandler optional error handler function. If defined, it is called every time a type-checking
  *                     error is encountered. If it returns false, type inference will proceed even if it
  *                     results in an invalid AST. If errorHandler is not defined or when it returns false,
  *                     a TypeException is thrown.
  */
case class TypeInfer(regime: TypeCheckingRegime = TypeCheckingRegime.Moderate,
                     allowNonWorkflowInputs: Boolean = true,
                     userDefinedFunctions: Vector[UserDefinedFunctionPrototype] = Vector.empty,
                     substituteFunctionsForTasks: Boolean = false,
                     fileResolver: FileSourceResolver = FileSourceResolver.get,
                     errorHandler: Option[TypeErrorHandler] = None,
                     logger: Logger = Logger.get) {
  private val unify = Unification(regime, logger)
  // TODO: handle warnings similarly to errors - either have TypeError take an ErrorKind parameter
  //  or have a separate warningHandler parameter
  private var errors = Vector.empty[TypeError]

  // get type restrictions, on a per-version basis
  private def getRuntimeTypeRestrictions(version: WdlVersion): Map[String, Vector[T]] = {
    version match {
      case WdlVersion.V2 =>
        Map(
            "container" -> Vector(T_Array(T_String, nonEmpty = true), T_String),
            "memory" -> Vector(T_Int, T_String),
            "cpu" -> Vector(T_Float, T_Int),
            "gpu" -> Vector(T_Boolean),
            "disks" -> Vector(T_Int, T_Array(T_String, nonEmpty = true), T_String),
            "maxRetries" -> Vector(T_Int),
            "returnCodes" -> Vector(T_Array(T_Int, nonEmpty = true), T_Int, T_String)
        )
      case _ =>
        Map(
            "docker" -> Vector(T_Array(T_String, nonEmpty = true), T_String),
            "memory" -> Vector(T_Int, T_String)
        )
    }
  }

  private def handleError(reason: String, locSource: SourceLocation): Unit = {
    errors = errors :+ TypeError(locSource, reason)
  }

  private def typeEvalExprGetName(expr: TAT.Expr, id: String, ctx: TypeContext): T = {
    expr.wdlType match {
      case T_Object =>
        // we can't know at this point whether the specified member exists in
        // the object, or what its type will be, so we allow it with type T_Any
        T_Any
      case struct: T_Struct =>
        struct.members.get(id) match {
          case None =>
            handleError(s"Struct ${struct.name} does not have member ${id}", expr.loc)
            expr.wdlType
          case Some(t) =>
            t
        }
      case call: T_Call =>
        call.output.get(id) match {
          case None =>
            handleError(s"Call ${call.name} does not have output ${id}", expr.loc)
            expr.wdlType
          case Some(t) =>
            t
        }
      case T_Identifier(structName) =>
        // An identifier is a struct, and we want to access a field in it. Examples:
        //  Person p = census.p
        //  String name = p.name
        ctx.aliases.get(structName) match {
          case None =>
            handleError(s"Unknown struct ${structName}", expr.loc)
            expr.wdlType
          case Some(struct: T_Struct) =>
            struct.members.get(id) match {
              case None =>
                handleError(s"Struct ${structName} does not have member ${id}", expr.loc)
                expr.wdlType
              case Some(t) => t
            }
          case other =>
            handleError(s"${other} is not a struct", expr.loc)
            expr.wdlType
        }

      // accessing a pair element
      case T_Pair(l, _) if id.toLowerCase() == "left"  => l
      case T_Pair(_, r) if id.toLowerCase() == "right" => r
      case T_Pair(_, _) =>
        handleError(s"Invalid pair accessor '${id}' (only 'left' and 'right' are allowed)",
                    expr.loc)
        expr.wdlType

      case _ =>
        handleError(s"Invalid member access ${expr}", expr.loc)
        expr.wdlType
    }
  }

  // unify a vector of types
  private def unifyTypes(types: Iterable[WdlTypes.T],
                         errMsg: String,
                         locSource: SourceLocation,
                         ctx: UnificationContext): T = {
    try {
      unify.apply(types, ctx)
    } catch {
      case _: TypeUnificationException =>
        handleError(errMsg + " must have the same type, or be coercible to one", locSource)
        types.head
    }
  }

  /**
    * Adds the type to an expression.
    */
  private def applyExpr(expr: AST.Expr,
                        ctx: TypeContext,
                        bindings: Bindings[String, T] = WdlTypeBindings.empty,
                        exprState: ExprState = ExprState.Start,
                        section: Section = Section.Other): TAT.Expr = {

    def canVectorize(applyExpr: AST.ExprApply): Boolean = {
      Operator.Vectorizable.contains(applyExpr.funcName) && (applyExpr.elements match {
        case Vector(_: AST.ExprArray) => false
        case _                        => true
      })
    }

    /*
     * Unwraps nested pairwise mathematical operations (+, -, *, /) and
     * turns them into a vector of arguments to vectorized functions,
     * e.g. an add(Vector[expr]) function.
     */
    def vectorize(applyExpr: AST.ExprApply): AST.ExprApply = {
      def getExpr(exprs: Vector[AST.Expr],
                  oper: Option[Operator],
                  loc: SourceLocation): AST.Expr = {
        if (oper.isDefined) {
          AST.ExprApply(oper.get.name,
                        Vector(AST.ExprArray(exprs)(SourceLocation.merge(exprs.map(_.loc)))))(loc)
        } else if (exprs.size == 1) {
          exprs.head
        } else {
          throw new TypeException(s"cannot vectorize ${oper} ${exprs}", loc)
        }
      }
      def inner(expr: AST.Expr): (Vector[AST.Expr], Option[Operator], SourceLocation) = {
        val (outerOper, lhs, rhs, loc) = expr match {
          case AST.ExprApply(funcName, Vector(lhs, rhs)) =>
            Operator.Vectorizable.get(funcName) match {
              case Some(oper) => (oper, lhs, rhs, expr.loc)
              case _          => return (Vector(expr), None, expr.loc)
            }
          case _ => return (Vector(expr), None, expr.loc)
        }
        // TODO: it is possible to also combine addition and multiplication
        //  operations where the RHS operator matches the outer operator, e.g
        //  1 + (2 + 3) == 1 + 2 + 3
        //  (1 * 2) * (3 * 4) == 1 * 2 * 3 * 4
        val (lhsExprs, lhsOper, _) = inner(lhs)
        lhsOper match {
          case Some(l) if outerOper == l =>
            (lhsExprs :+ rhs, Some(outerOper), loc)
          case _ =>
            (Vector(lhs, rhs), Some(outerOper), loc)
        }
      }
      val (exprs, oper, loc) = inner(applyExpr)
      getExpr(exprs, oper, loc) match {
        case a: AST.ExprApply => a
        case other =>
          throw new Exception(s"expected result to be an AST.ExprApply, not ${other}")
      }
    }

    def nestedStringExpr(expr: AST.Expr,
                         nestedState: ExprState.ExprState,
                         exprType: WdlTypes.T = T_String,
                         initialType: WdlTypes.T = T_String): (TAT.Expr, WdlTypes.T) = {
      val e2 = nested(expr, nestedState)
      // check that the expression is coercible to a string - we also allow optional because
      // null/None/undefined value auto-coerces to the empty string within a placeholder
      val unifyCtx = UnificationContext(Some(ctx.version), section, inPlaceholder = true)
      val coerces = unify.isCoercibleTo(T_String, e2.wdlType, unifyCtx) ||
        unify.isCoercibleTo(T_Optional(T_String), e2.wdlType, unifyCtx)
      val t2: T = if (coerces) {
        exprType
      } else {
        handleError(
            s"expression ${prettyFormatExpr(e2)} of type ${e2.wdlType} is not coercible to string",
            expr.loc
        )
        if (exprType == initialType) {
          e2.wdlType
        } else {
          exprType
        }
      }
      (e2, t2)
    }

    def nested(nestedExpr: AST.Expr, nestedState: ExprState): TAT.Expr = {
      nestedExpr match {
        // interpolation
        case e: AST.ExprCompoundString =>
          // if we're not already in a string, being in a ExprCompoundString
          // guarantees that we are
          val nextState = if (nestedState >= ExprState.InString) {
            nestedState
          } else {
            ExprState.InString
          }
          // All the sub-exressions have to be strings, or coercible to strings
          val initialType: T = T_String
          val (vec2, wdlType) = e.value.foldLeft((Vector.empty[TAT.Expr], initialType)) {
            case ((v, t), subExpr) =>
              val (e2, t2) = nestedStringExpr(subExpr, nextState, t, initialType)
              (v :+ e2, t2)
          }
          TAT.ExprCompoundString(vec2, wdlType, e.quoting)(e.loc)
        case _ =>
          // the next state for any other nested expression - if we're already in a String,
          // then any nested expression must occur within a placeholder
          val nextState = if (nestedState == ExprState.InString) {
            ExprState.InPlaceholder
          } else {
            nestedState
          }
          nestedExpr match {
            // None can be any type optional
            case e: AST.ValueNone    => TAT.ValueNone(T_Optional(T_Any))(e.loc)
            case e: AST.ValueBoolean => TAT.ValueBoolean(e.value, T_Boolean)(e.loc)
            case e: AST.ValueInt     => TAT.ValueInt(e.value, T_Int)(e.loc)
            case e: AST.ValueFloat   => TAT.ValueFloat(e.value, T_Float)(e.loc)
            case e: AST.ValueString  => TAT.ValueString(e.value, T_String, e.quoting)(e.loc)

            // complex types
            case e: AST.ExprPair =>
              val l2 = nested(e.left, nextState)
              val r2 = nested(e.right, nextState)
              val t = T_Pair(l2.wdlType, r2.wdlType)
              TAT.ExprPair(l2, r2, t)(e.loc)
            case e: AST.ExprArray if e.value.isEmpty =>
              // The array is empty, we can't tell what the array type is.
              TAT.ExprArray(Vector.empty, T_Array(T_Any))(e.loc)
            case e: AST.ExprArray =>
              val tItems = e.value.map(e => nested(e, nextState))
              val unifyCtx =
                UnificationContext(Some(ctx.version),
                                   section,
                                   inPlaceholder = nextState >= ExprState.InPlaceholder)
              val wdlTypes = tItems.map(_.wdlType)
              val itemType =
                try {
                  // this is a non-empty array literal, so we can set nonEmpty = true
                  T_Array(unify.apply(wdlTypes, unifyCtx), nonEmpty = true)
                } catch {
                  case _: TypeUnificationException =>
                    handleError(
                        s"""array ${e.value} contains multiple incompatible data types
                           |${wdlTypes.toSet.mkString(",")}""".stripMargin
                          .replaceAll("\n", " "),
                        nestedExpr.loc
                    )
                    tItems.head.wdlType
                }
              TAT.ExprArray(tItems, itemType)(e.loc)
            case e: AST.ExprObject =>
              val tMembers = e.value
                .map {
                  case AST.ExprMember(key, value) =>
                    val k = nested(key, nextState)
                    val v = nested(value, nextState)
                    (k, v)
                }
                .to(TreeSeqMap)
              TAT.ExprObject(tMembers, T_Object)(e.loc)
            case e: AST.ExprStruct =>
              val tMembers = e.members
                .map {
                  case AST.ExprMember(key, value) =>
                    val k = nested(key, nextState)
                    val v = nested(value, nextState)
                    (k, v)
                }
                .to(TreeSeqMap)
              val wdlType = ctx.aliases.get(e.name) match {
                case Some(structType: T_Struct) => structType
                case _ =>
                  handleError(s"Struct type ${e.name} is not defined", nestedExpr.loc)
                  T_Object
              }
              TAT.ExprObject(tMembers, wdlType)(e.loc)
            case e: AST.ExprMap if e.value.isEmpty =>
              // The key and value types are unknown.
              TAT.ExprMap(SeqMap.empty, T_Map(T_Any, T_Any))(e.loc)
            case e: AST.ExprMap =>
              val tMembers = e.value
                .map { item: AST.ExprMember =>
                  val k = nested(item.key, nextState)
                  val v = nested(item.value, nextState)
                  (k, v)
                }
                .to(TreeSeqMap)
              // unify the key/value types
              val unifyCtx =
                UnificationContext(Some(ctx.version), section, nextState >= ExprState.InPlaceholder)
              val (keys, values) = tMembers.unzip
              val keyType = unifyTypes(keys.map(_.wdlType), "map keys", e.loc, unifyCtx) match {
                case t: T_Primitive => t
                case t =>
                  handleError(s"Map key type ${t} is not primitive", nestedExpr.loc)
                  t
              }
              val valueType = unifyTypes(values.map(_.wdlType), "map values", e.loc, unifyCtx)
              TAT.ExprMap(tMembers, T_Map(keyType, valueType))(e.loc)

            case e: AST.ExprIdentifier =>
              // an identifier has to be bound to a known type. Lookup the the type,
              // and add it to the expression.
              val t = ctx.lookup(e.id, bindings) match {
                case Some(t) => t
                case None =>
                  handleError(s"Identifier ${e.id} is not defined", nestedExpr.loc)
                  T_Any
              }
              TAT.ExprIdentifier(e.id, t)(e.loc)

            case e: AST.ExprPlaceholder =>
              val unifyCtx =
                UnificationContext(Some(ctx.version), section, nextState >= ExprState.InPlaceholder)
              val valueExpr = nested(e.value, ExprState.InPlaceholder)

              val (defaultExpr, defaultValueType) = e.defaultOpt
                .map { d =>
                  val (defaultExpr, defaultType) = nestedStringExpr(d, ExprState.InPlaceholder)
                  val t = TypeUtils.unwrapOptional(valueExpr.wdlType) match {
                    case vt if unify.isCoercibleTo(defaultType, vt, unifyCtx) =>
                      // the value is supposed to be optional but is not - we allow it anyway
                      defaultType
                    case vt if unify.isCoercibleTo(T_String, vt, unifyCtx) =>
                      // the value is supposed to be optional but is not - we allow it anyway
                      T_String
                    case T_Array(vt, _)
                        if e.sepOpt.isDefined && unify.isCoercibleTo(T_String, vt, unifyCtx) =>
                      // mixing default and sep - not technically allowed but we support it
                      // since it is used in some "industry standard" workflows
                      T_String
                    case _ =>
                      handleError(
                          s"""|Expression ${prettyFormatExpr(valueExpr)} has type
                              |${prettyFormatType(valueExpr.wdlType)}, which is not coercible to
                              |${prettyFormatType(defaultExpr.wdlType)}""".stripMargin
                            .replaceAll("\n", " "),
                          nestedExpr.loc
                      )
                      valueExpr.wdlType
                  }
                  (Some(defaultExpr), Some(t))
                }
                .getOrElse((None, None))

              val tOption = e.trueOpt.map(nestedStringExpr(_, ExprState.InPlaceholder))
              val fOption = e.falseOpt.map(nestedStringExpr(_, ExprState.InPlaceholder))
              val sepOption = e.sepOpt.map(nestedStringExpr(_, ExprState.InPlaceholder))

              (tOption, fOption, sepOption) match {
                case (Some((trueExpr, trueType)), Some((falseExpr, falseType)), None) =>
                  val valueType = if (defaultValueType.isDefined) {
                    TypeUtils.unwrapOptional(valueExpr.wdlType)
                  } else {
                    valueExpr.wdlType
                  }
                  if (!unify.isCoercibleTo(T_Boolean, valueType, unifyCtx)) {
                    val msg =
                      s"""Condition ${prettyFormatExpr(valueExpr)} has type
                         |${prettyFormatType(valueExpr.wdlType)}, which is not 
                         |coercible to Boolean""".stripMargin.replaceAll("\n", " ")
                    handleError(msg, nestedExpr.loc)
                  }
                  val wdlType = (trueType, falseType, defaultValueType) match {
                    case (t, f, Some(d)) if t == f && t == d => t
                    case (t, f, None) if t == f              => t
                    case _                                   => T_String
                  }
                  TAT.ExprPlaceholder(Some(trueExpr),
                                      Some(falseExpr),
                                      None,
                                      defaultExpr,
                                      valueExpr,
                                      wdlType)(e.loc)
                case (None, None, Some((sepExpr, _))) =>
                  val valueType = if (defaultValueType.isDefined) {
                    TypeUtils.unwrapOptional(valueExpr.wdlType)
                  } else {
                    valueExpr.wdlType
                  }
                  val t = if (unify.isCoercibleTo(T_Array(T_String), valueType, unifyCtx)) {
                    T_String
                  } else {
                    val msg =
                      s"""Expression ${prettyFormatExpr(valueExpr)} has type 
                         |${prettyFormatType(valueType)}, which is not coercible 
                         |to Array[String]""".stripMargin
                        .replaceAll("\n", " ")
                    handleError(msg, valueExpr.loc)
                    valueType
                  }
                  val wdlType = (t, defaultValueType) match {
                    case (t, Some(d)) if t == d => t
                    case _                      => T_String
                  }
                  TAT.ExprPlaceholder(None, None, Some(sepExpr), defaultExpr, valueExpr, wdlType)(
                      e.loc
                  )
                case (None, None, None) =>
                  TAT.ExprPlaceholder(None,
                                      None,
                                      None,
                                      defaultExpr,
                                      valueExpr,
                                      defaultValueType.get)(e.loc)
                case _ =>
                  throw new Exception(s"invalid combination of placeholder options: ${nestedExpr}")
              }

            case e: AST.ExprIfThenElse =>
              // if (x == 1) then "Sunday" else "Weekday"
              val eCond = nested(e.cond, nextState)
              val eTrueBranch = nested(e.trueExpr, nextState)
              val eFalseBranch = nested(e.falseExpr, nextState)
              val t = {
                if (eCond.wdlType != T_Boolean) {
                  handleError(
                      s"""Condition ${prettyFormatExpr(eCond)} has type ${eCond.wdlType}, 
                         |which is not coercible to Boolean""".stripMargin.replaceAll("\n", " "),
                      eCond.loc
                  )
                  eCond.wdlType
                } else {
                  try {
                    val unifyCtx = UnificationContext(Some(ctx.version),
                                                      section,
                                                      nextState >= ExprState.InPlaceholder)
                    unify.apply(eTrueBranch.wdlType, eFalseBranch.wdlType, unifyCtx)
                  } catch {
                    case _: TypeUnificationException =>
                      val msg =
                        s"""|Conditional branches ${prettyFormatType(eTrueBranch.wdlType)},
                            |${prettyFormatType(eFalseBranch.wdlType)} are not coercible to
                            |a common type""".stripMargin.replaceAll("\n", " ")
                      handleError(msg, nestedExpr.loc)
                      eTrueBranch.wdlType
                  }
                }
              }
              TAT.ExprIfThenElse(eCond, eTrueBranch, eFalseBranch, t)(e.loc)

            case e: AST.ExprAt =>
              // Access an array element at [index: Int] or a map value at [key: K]
              val eIndex = nested(e.index, nextState)
              val eCollection = nested(e.array, nextState)
              val t = (eIndex.wdlType, eCollection.wdlType) match {
                case (T_Int, T_Array(elementType, _)) =>
                  elementType
                case (iType, T_Map(kType, vType))
                    if unify.isCoercibleTo(
                        kType,
                        iType,
                        UnificationContext(Some(ctx.version),
                                           section,
                                           nextState >= ExprState.InPlaceholder)
                    ) =>
                  vType
                case (T_Int, _) =>
                  handleError(s"Expression ${prettyFormatExpr(eCollection)} must be an array",
                              eCollection.loc)

                  eIndex.wdlType
                case (_, _) =>
                  handleError(
                      s"""${prettyFormatExpr(eIndex)} is not a valid index for collection 
                         |${prettyFormatExpr(eCollection)}""".stripMargin.replaceAll("\n", " "),
                      e.loc
                  )
                  eCollection.wdlType
              }
              TAT.ExprAt(eCollection, eIndex, t)(e.loc)

            case e: AST.ExprGetName =>
              // Access a field in a struct or an object. For example "x.a" in:
              //   Int z = x.a
              val lhsExpr = nested(e.expr, nextState)
              val wdlType = typeEvalExprGetName(lhsExpr, e.id, ctx)
              TAT.ExprGetName(lhsExpr, e.id, wdlType)(e.loc)

            // vectorize any mathematical operations
            case applyExpr: AST.ExprApply if canVectorize(applyExpr) =>
              nested(vectorize(applyExpr), nestedState)
            case e: AST.ExprApply =>
              // Apply a standard library function to arguments. For example:
              //   read_int("4")
              val tElements = e.elements.map(e => nested(e, nextState))
              try {
                val (outputType, funcSig) =
                  ctx.stdlib.apply(e.funcName, tElements.map(_.wdlType), nextState)
                TAT.ExprApply(e.funcName, funcSig, tElements, outputType)(e.loc)
              } catch {
                case e: StdlibFunctionException =>
                  handleError(e.getMessage, nestedExpr.loc)
                  TAT.ValueNone(T_Any)(nestedExpr.loc)
              }
          }
      }
    }
    nested(expr, exprState)
  }

  private def typeFromAst(t: AST.Type, loc: SourceLocation, ctx: TypeContext): T = {
    t match {
      case _: AST.TypeBoolean         => T_Boolean
      case _: AST.TypeInt             => T_Int
      case _: AST.TypeFloat           => T_Float
      case _: AST.TypeString          => T_String
      case _: AST.TypeFile            => T_File
      case _: AST.TypeDirectory       => T_Directory
      case AST.TypeOptional(t)        => T_Optional(typeFromAst(t, loc, ctx))
      case AST.TypeArray(t, nonEmpty) => T_Array(typeFromAst(t, loc, ctx), nonEmpty = nonEmpty)
      case AST.TypeMap(k, v)          => T_Map(typeFromAst(k, loc, ctx), typeFromAst(v, loc, ctx))
      case AST.TypePair(l, r)         => T_Pair(typeFromAst(l, loc, ctx), typeFromAst(r, loc, ctx))
      case AST.TypeIdentifier(id) =>
        ctx.aliases.get(id) match {
          case None =>
            handleError(s"struct ${id} has not been defined", loc)
            T_Any
          case Some(struct) => struct
        }
      case _: AST.TypeObject => T_Object
      case AST.TypeStruct(name, members) =>
        T_Struct(name,
                 members
                   .map {
                     case AST.StructMember(name, t2) => name -> typeFromAst(t2, loc, ctx)
                   }
                   .to(TreeSeqMap))
    }
  }

  private def isReadFunction(expr: AST.Expr): Boolean = {
    expr match {
      case AST.ExprApply(funcName, _) if funcName.startsWith("read_") => true
      case _                                                          => false
    }
  }

  private def translateDeclaration(
      decl: AST.Declaration,
      section: Section,
      ctx: TypeContext,
      bindings: Bindings[String, T],
      canShadow: Boolean = false
  ): (WdlTypes.T, Option[TAT.Expr], Bindings[String, T]) = {
    val lhsType = typeFromAst(decl.wdlType, decl.loc, ctx)
    val (wdlType, tExpr) = decl.expr match {
      // Int x
      case None => (lhsType, None)
      case Some(expr) =>
        val e = applyExpr(expr, ctx, bindings, section = section)
        val rhsType = e.wdlType
        val unifyCtx =
          UnificationContext(Some(ctx.version), section, inReadFunction = isReadFunction(expr))
        val wdlType = if (unify.isCoercibleTo(lhsType, rhsType, unifyCtx)) {
          lhsType
        } else {
          val msg =
            s"""|${decl.name} value ${prettyFormatExpr(e)} of type ${prettyFormatType(rhsType)}
                |is not coercible to ${prettyFormatType(lhsType)}""".stripMargin
              .replaceAll("\n", " ")
          handleError(msg, decl.loc)
          lhsType
        }
        (wdlType, Some(e))
    }
    // There are cases where we want to allow shadowing. For example, it
    // is legal to have an output variable with the same name as an input variable.
    if (!canShadow && ctx.lookup(decl.name, bindings).isDefined) {
      handleError(s"variable ${decl.name} shadows an existing variable", decl.loc)
    }
    (wdlType, tExpr, bindings.add(decl.name, wdlType))
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
  private def applyDeclaration(
      decl: AST.Declaration,
      section: Section,
      ctx: TypeContext,
      bindings: Bindings[String, T],
      canShadow: Boolean = false
  ): (TAT.PrivateVariable, Bindings[String, T]) = {
    translateDeclaration(decl, section, ctx, bindings, canShadow) match {
      case (wdlType, Some(tExpr), newBindings) =>
        (TAT.PrivateVariable(decl.name, wdlType, tExpr)(decl.loc), newBindings)
      case (wdlType, None, newBindings) =>
        handleError(s"Private variable ${decl.name} must have an expression", decl.loc)
        (TAT.PrivateVariable(decl.name, wdlType, TAT.ValueNull(wdlType)(decl.loc))(decl.loc),
         newBindings)
    }
  }

  // type check the input section, and see that there are no double definitions.
  // return input definitions
  //
  private def applyInputSection(inputSection: AST.InputSection,
                                ctx: TypeContext): (Vector[TAT.InputParameter], TypeContext) = {
    val init: Bindings[String, T] = WdlTypeBindings.empty
    val (tInputParams, _, _) =
      inputSection.parameters
        .foldLeft((Vector.empty[TAT.InputParameter], Set.empty[String], init)) {
          case ((tInputParams, names, bindings), decl) =>
            if (names.contains(decl.name)) {
              handleError(s"Input section has duplicate definition ${decl.name}", inputSection.loc)
            }
            val (wdlType, tExpr, afterBindings) =
              translateDeclaration(decl, Section.Input, ctx, bindings)
            val tParam = (wdlType, tExpr) match {
              case (t: WdlTypes.T_Optional, None) =>
                TAT.OptionalInputParameter(decl.name, t)(decl.loc)
              case (_, None) =>
                TAT.RequiredInputParameter(decl.name, wdlType)(decl.loc)
              case (t, Some(expr)) =>
                TAT.OverridableInputParameterWithDefault(decl.name, t, expr)(decl.loc)
            }
            (tInputParams :+ tParam, names + decl.name, afterBindings)
        }
    val afterCtx = ctx.bindInputSection(tInputParams)
    (tInputParams, afterCtx)
  }

  // Calculate types for the outputs, and return a new typed output section
  private def applyOutputSection(outputSection: AST.OutputSection,
                                 ctx: TypeContext): (Vector[TAT.OutputParameter], TypeContext) = {
    // output variables can shadow input definitions, but not intermediate
    // values. This is weird, but is used here:
    // https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/tasks/JointGenotypingTasks-terra.wdl#L590
    val both = outputSection.parameters.map(_.name).toSet intersect ctx.declarations.keySet
    val init: Bindings[String, T] = WdlTypeBindings.empty
    val (tOutputParams, _, _) =
      outputSection.parameters
        .foldLeft((Vector.empty[TAT.OutputParameter], Set.empty[String], init)) {
          case ((tOutputParams, names, bindings), decl) =>
            // check the declaration and add a binding for its (variable -> wdlType)
            if (names.contains(decl.name)) {
              handleError(s"Output section has duplicate definition ${decl.name}", decl.loc)
            }
            if (both.contains(decl.name)) {
              handleError(s"Definition ${decl.name} shadows exisiting declarations", decl.loc)
            }
            val (wdlType, tExpr, afterBindings) =
              translateDeclaration(decl, Section.Output, ctx, bindings, canShadow = true)
            val tOutputParam = tExpr match {
              case Some(tExpr) =>
                TAT.OutputParameter(decl.name, wdlType, tExpr)(decl.loc)
              case _ =>
                throw new TypeException("Outputs must have expressions", decl.loc)
            }
            (tOutputParams :+ tOutputParam, names + decl.name, afterBindings)
        }
    val afterCtx = ctx.bindOutputSection(tOutputParams)
    (tOutputParams, afterCtx)
  }

  // The runtime section can make use of values defined in declarations
  private def applyRuntime(rtSection: AST.RuntimeSection, ctx: TypeContext): TAT.RuntimeSection = {
    val restrictions = getRuntimeTypeRestrictions(ctx.version)
    val unifyCtx = UnificationContext(Some(ctx.version))
    val m = rtSection.kvs
      .map { kv: AST.RuntimeKV =>
        val tExpr = applyExpr(kv.expr, ctx)
        // if there are type restrictions, check that the type of the expression is coercible to
        // one of the allowed types
        if (!restrictions
              .get(kv.id)
              .forall(_.exists(t => unify.isCoercibleTo(t, tExpr.wdlType, unifyCtx)))) {
          throw new TypeException(
              s"runtime id ${kv.id} is not coercible to one of the allowed types ${restrictions(kv.id)}",
              kv.loc
          )
        }
        kv.id -> tExpr
      }
      .to(TreeSeqMap)
    TAT.RuntimeSection(m)(rtSection.loc)
  }

  // convert the generic expression syntax into a specialized JSON object
  // language for meta values only.
  private def applyMetaValue(expr: AST.MetaValue, ctx: TypeContext): TAT.MetaValue = {
    expr match {
      case v: AST.MetaValueNull    => TAT.MetaValueNull()(v.loc)
      case v: AST.MetaValueBoolean => TAT.MetaValueBoolean(v.value)(v.loc)
      case v: AST.MetaValueInt     => TAT.MetaValueInt(v.value)(v.loc)
      case v: AST.MetaValueFloat   => TAT.MetaValueFloat(v.value)(v.loc)
      case v: AST.MetaValueString  => TAT.MetaValueString(v.value, v.quoting)(v.loc)
      case v: AST.MetaValueArray =>
        TAT.MetaValueArray(v.value.map(applyMetaValue(_, ctx)))(v.loc)
      case v: AST.MetaValueObject =>
        TAT.MetaValueObject(
            v.value
              .map {
                case AST.MetaKV(key, value) =>
                  key -> applyMetaValue(value, ctx)
              }
              .to(TreeSeqMap)
        )(
            v.loc
        )
      case other =>
        handleError(s"${SUtil.prettyFormatMetaValue(other)} is an invalid meta value", expr.loc)
        TAT.MetaValueNull()(other.loc)
    }
  }

  private def applyMeta(metaSection: AST.MetaSection, ctx: TypeContext): TAT.MetaSection = {
    TAT.MetaSection(
        metaSection.kvs
          .map {
            case AST.MetaKV(k, v) =>
              k -> applyMetaValue(v, ctx)
          }
          .to(TreeSeqMap)
    )(metaSection.loc)
  }

  private def applyParamMeta(paramMetaSection: AST.ParameterMetaSection,
                             ctx: TypeContext): TAT.MetaSection = {
    TAT.MetaSection(
        paramMetaSection.kvs
          .flatMap { kv: AST.MetaKV =>
            val metaValue = if (ctx.inputs.contains(kv.id) || ctx.outputs.contains(kv.id)) {
              Some(applyMetaValue(kv.value, ctx))
            } else if (regime < TypeCheckingRegime.Strict) {
              logger.warning(
                  s"""parameter_meta key ${kv.id} is being ignored because it does not refer to 
                     |an input or output declaration""".stripMargin.replaceAll("\n", " ")
              )
              None
            } else {
              handleError(
                  s"parameter_meta key ${kv.id} does not refer to an input or output declaration",
                  kv.loc
              )
              None
            }
            metaValue.map(kv.id -> _)
          }
          .to(TreeSeqMap)
    )(
        paramMetaSection.loc
    )
  }

  private def applyHints(hintsSection: AST.HintsSection, ctx: TypeContext): TAT.MetaSection = {
    TAT.MetaSection(
        hintsSection.kvs
          .map {
            case AST.MetaKV(k, v) =>
              k -> applyMetaValue(v, ctx)
          }
          .to(TreeSeqMap)
    )(
        hintsSection.loc
    )
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(
      inputSection: Vector[TAT.InputParameter],
      outputSection: Vector[TAT.OutputParameter]
  ): (SeqMap[String, (WdlTypes.T, Boolean)], SeqMap[String, WdlTypes.T]) = {
    val inputType: SeqMap[String, (WdlTypes.T, Boolean)] = inputSection
      .map {
        case d: TAT.RequiredInputParameter =>
          // input is compulsory
          d.name -> (d.wdlType, false)
        case d: TAT.OverridableInputParameterWithDefault =>
          // input has a default value, caller may omit it.
          d.name -> (d.wdlType, true)
        case d: TAT.OptionalInputParameter =>
          // input is optional, caller can omit it.
          d.name -> (d.wdlType, true)
      }
      .to(TreeSeqMap)

    val outputType: SeqMap[String, WdlTypes.T] = outputSection
      .map { tDecl =>
        tDecl.name -> tDecl.wdlType
      }
      .to(TreeSeqMap)

    (inputType, outputType)
  }

  // TASK
  //
  // - An inputs type has to match the type of its default value (if any)
  // - Check the declarations
  // - Assignments to an output variable must match
  //
  // We can't check the validity of the command section.
  private def applyTask(task: AST.Task, ctx: TypeContext): TAT.Task = {
    val (inputDefs, inputCtx) = task.input match {
      case None               => (Vector.empty, ctx)
      case Some(inputSection) => applyInputSection(inputSection, ctx)
    }

    // add types to the private variables, and accumulate context
    val init: Bindings[String, T] = WdlTypeBindings.empty
    val (tDeclarations, _) =
      task.privateVariables.foldLeft((Vector.empty[TAT.PrivateVariable], init)) {
        case ((tDecls, bindings), decl) =>
          val (tDecl, afterBindings) = applyDeclaration(decl, Section.Other, inputCtx, bindings)
          (tDecls :+ tDecl, afterBindings)
      }
    val declCtx = tDeclarations.foldLeft(inputCtx) {
      case (beforeCtx, tDecl) =>
        try {
          beforeCtx.bindDeclaration(tDecl.name, tDecl.wdlType)
        } catch {
          case e: DuplicateBindingException =>
            handleError(e.getMessage, tDecl.loc)
            beforeCtx
        }
    }

    val tRuntime = task.runtime.map(applyRuntime(_, declCtx))
    val tHints = task.hints.map(applyHints(_, declCtx))

    // Check that all expressions can be coereced to a string inside
    // the command section. If type is T_Any we allow it here - the
    // actual type will be checked at runtime.
    val tCommandParts = task.command.parts.map { expr =>
      val e = applyExpr(expr, declCtx, exprState = ExprState.InString)
      e.wdlType match {
        case x if isPrimitive(x)             => e
        case T_Optional(x) if isPrimitive(x) => e
        case T_Any                           => e
        case other =>
          handleError(
              s"""Expression ${prettyFormatExpr(e)} in the command section has type ${other},
                 |which is not coercible to a string""".stripMargin.replaceAll("\n", " "),
              expr.loc
          )
          TAT.ValueString(prettyFormatExpr(e), T_String)(e.loc)
      }
    }
    val tCommand = TAT.CommandSection(tCommandParts)(task.command.loc)

    val (outputDefs, outputCtx) = task.output match {
      case None             => (Vector.empty, declCtx)
      case Some(outSection) => applyOutputSection(outSection, declCtx)
    }

    val tMeta = task.meta.map(applyMeta(_, outputCtx))
    val tParamMeta = task.parameterMeta.map(applyParamMeta(_, outputCtx))

    // calculate the type signature of the task
    val taskName = task.name
    val (inputType, outputType) = calcSignature(inputDefs, outputDefs)
    val function = if (allowNonWorkflowInputs) {
      userDefinedFunctions.iterator
        .map(udf => udf.getTaskProxyFunction(taskName, inputType, outputType))
        .collectFirst {
          case proto if proto.isDefined => proto.get
        }
    } else {
      None
    }
    val taskType = T_Task(taskName, inputType, outputType, function)
    TAT.Task(taskName,
             taskType,
             inputDefs,
             outputDefs,
             tCommand,
             tDeclarations,
             tMeta,
             tParamMeta,
             tRuntime,
             tHints)(task.loc)
  }

  // 1. all the caller arguments have to exist with the correct types
  //    in the callee
  // 2. all the compulsory callee arguments must be specified. Optionals
  //    and arguments that have defaults can be skipped.
  private def applyCall(call: AST.Call, ctx: TypeContext): TAT.Call = {
    // The name of the call may or may not contain dots. Examples:
    //
    // call lib.concat as concat     concat
    // call add                      add
    // call a.b.c                    c
    val unqualifiedName = call.name match {
      case name if name contains "." => name.split("\\.").last
      case name                      => name
    }
    val actualName = call.alias match {
      case Some(alias) => alias.name
      case None        => unqualifiedName
    }

    // check that the call refers to a valid callee
    val callee: T_Callable = ctx.callables.get(call.name) match {
      case None =>
        handleError(s"called task/workflow ${call.name} is not defined", call.loc)
        WdlTypes.T_Task(call.name, SeqMap.empty, SeqMap.empty, None)
      case Some(x: T_Callable) => x
    }

    // check if the call shadows an existing call
    val callType = T_Call(actualName, callee.output)
    val wdlType = if (ctx.declarations contains actualName) {
      handleError(s"call ${actualName} shadows an existing definition", call.loc)
      T_Call(actualName, SeqMap.empty)
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
              after.loc
          )
          T_Call(after.name, SeqMap.empty)
        case None =>
          handleError(s"call ${actualName} after clause refers to non-existant call ${after.name}",
                      after.loc)
          T_Call(after.name, SeqMap.empty)
      }
    }

    // convert inputs
    val callerInputs: SeqMap[String, TAT.Expr] = call.inputs match {
      case Some(AST.CallInputs(value)) =>
        value
          .map { inp =>
            val paramName = inp.name
            val tExpr = applyExpr(inp.expr, ctx, section = Section.Call)
            if (callee.input.contains(paramName)) {
              // type-check input argument
              val (calleeInputType, optional) = callee.input(paramName)
              val checkType = if (optional) {
                TypeUtils.ensureOptional(calleeInputType)
              } else {
                TypeUtils.unwrapOptional(calleeInputType)
              }
              if (!unify.isCoercibleTo(checkType,
                                       tExpr.wdlType,
                                       UnificationContext(Some(ctx.version), Section.Call))) {
                handleError(
                    s"""argument to parameter '${paramName}' has type ${tExpr.wdlType}, 
                       |it is not coercible to ${checkType}""".stripMargin.replaceAll("\n", " "),
                    call.loc
                )
              }
            } else {
              handleError(
                  s"call '${call.name}' has argument ${paramName} that does not exist in the callee",
                  call.loc
              )
            }
            paramName -> tExpr
          }
          .to(TreeSeqMap)
      case None => SeqMap.empty
    }

    // check that all the compulsory arguments are provided
    val missingInputs = callee.input.flatMap {
      case (argName, (wdlType, false)) =>
        callerInputs.get(argName) match {
          case None if allowNonWorkflowInputs =>
            logger.warning(
                s"compulsory argument '${argName}' to task/workflow ${call.name} is missing"
            )
            Some(argName -> TAT.ValueNone(wdlType)(call.loc))
          case None =>
            handleError(
                s"compulsory argument '${argName}' to task/workflow ${call.name} is missing",
                call.loc
            )
            Some(argName -> TAT.ValueNone(wdlType)(call.loc))
          case Some(_) =>
            // argument is provided
            None
        }
      case (_, (_, true)) =>
        // an optional argument, it may not be provided
        None
    }

    // convert the alias to a simpler string option
    val alias = call.alias match {
      case None                      => None
      case Some(AST.CallAlias(name)) => Some(name)
    }

    TAT.Call(unqualifiedName,
             call.name,
             wdlType,
             callee,
             alias,
             afters,
             actualName,
             callerInputs ++ missingInputs)(
        call.loc
    )
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
  private def applyScatter(scatter: AST.Scatter,
                           ctx: TypeContext): (TAT.Scatter, WdlTypeBindings) = {
    val collection = applyExpr(scatter.expr, ctx)
    val (elementType, nonEmpty) = collection.wdlType match {
      case T_Array(elementType, nonEmpty) => (elementType, nonEmpty)
      case other =>
        handleError(s"Scatter collection ${scatter.expr} is not an array type", scatter.loc)
        (other, false)
    }

    // add a binding for the iteration variable
    //
    // The iterator identifier is not exported outside the scatter
    val bodyCtx =
      try {
        ctx.bindDeclaration(scatter.identifier, elementType)
      } catch {
        case e: DuplicateBindingException =>
          handleError(e.getMessage, scatter.loc)
          ctx
      }

    val (tBody, bindings) = applyWorkflowElements(scatter.body, bodyCtx)
    assert(!bindings.contains(scatter.identifier))

    val tScatter = TAT.Scatter(scatter.identifier, collection, tBody)(scatter.loc)

    // Add an array type to all variables defined in the scatter body
    // if the scatter collection is non-empty, then we know the output
    // arrays also have to be non-empty
    val gatherBindings =
      bindings.toMap.map {
        case (callName, callType: T_Call) =>
          val callOutput = callType.output.map {
            case (name, t) => name -> T_Array(t, nonEmpty = nonEmpty)
          }
          callName -> T_Call(callType.name, callOutput)
        case (varName, varType: T) =>
          varName -> T_Array(varType, nonEmpty = nonEmpty)
      }

    (tScatter, WdlTypeBindings(gatherBindings))
  }

  // The body of a conditional is accessible to the statements that come after it.
  // This is different than the scoping rules for other programming languages.
  //
  // Add an optional modifier to all the types inside the body.
  private def applyConditional(cond: AST.Conditional,
                               ctx: TypeContext): (TAT.Conditional, WdlTypeBindings) = {
    val condExpr = applyExpr(cond.expr, ctx) match {
      case e if e.wdlType == T_Boolean => e
      case e =>
        handleError(s"Expression ${prettyFormatExpr(e)} must have boolean type", cond.loc)
        TAT.ValueBoolean(value = false, T_Boolean)(e.loc)
    }

    // keep track of the inner/outer bindings. Within the block we need [inner],
    // [outer] is what we produce, which has the optional modifier applied to
    // everything.
    val (wfElements, bindings) = applyWorkflowElements(cond.body, ctx)

    val optionalBindings =
      bindings.toMap.map {
        case (callName, callType: T_Call) =>
          val callOutput = callType.output.map {
            case (name, t) => name -> TypeUtils.ensureOptional(t)
          }
          callName -> T_Call(callType.name, callOutput)
        case (varName, typ: WdlTypes.T) =>
          varName -> TypeUtils.ensureOptional(typ)
      }

    (TAT.Conditional(condExpr, wfElements)(cond.loc), WdlTypeBindings(optionalBindings))
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
      body: Vector[AST.WorkflowElement],
      ctx: TypeContext
  ): (Vector[TAT.WorkflowElement], Bindings[String, T]) = {
    val init: Bindings[String, T] = WdlTypeBindings.empty
    body.foldLeft((Vector.empty[TAT.WorkflowElement], init)) {
      case ((tElements, bindings), decl: AST.Declaration) =>
        val (tDecl, afterBindings) = applyDeclaration(decl, Section.Other, ctx, bindings)
        (tElements :+ tDecl, afterBindings)

      case ((tElements, bindings), wfElement) =>
        val newCtx =
          try {
            ctx.bindDeclarations(bindings)
          } catch {
            case e: DuplicateBindingException =>
              handleError(e.getMessage, wfElement.loc)
              ctx
          }
        wfElement match {
          case call: AST.Call =>
            val tCall = applyCall(call, newCtx)
            (tElements :+ tCall, bindings.add(tCall.actualName, tCall.wdlType))

          case scatter: AST.Scatter =>
            // a nested scatter
            val (tScatter, scatterBindings) = applyScatter(scatter, newCtx)
            (tElements :+ tScatter, bindings.addAll(scatterBindings))

          case cond: AST.Conditional =>
            // a nested conditional
            val (tCond, condBindings) = applyConditional(cond, newCtx)
            (tElements :+ tCond, bindings.addAll(condBindings))

          case other => throw new RuntimeException(s"Unexpected workflow element ${other}")
        }
    }
  }

  private def applyWorkflow(wf: AST.Workflow, ctx: TypeContext): TAT.Workflow = {
    val (inputDefs, inputCtx) = wf.input match {
      case None               => (Vector.empty, ctx)
      case Some(inputSection) => applyInputSection(inputSection, ctx)
    }
    val (wfElements, bindings) = applyWorkflowElements(wf.body, inputCtx)
    val bodyCtx = inputCtx.bindDeclarations(bindings)
    val (outputDefs, outputCtx) = wf.output match {
      case None                => (Vector.empty, bodyCtx)
      case Some(outputSection) => applyOutputSection(outputSection, bodyCtx)
    }
    val tMeta = wf.meta.map(applyMeta(_, outputCtx))
    val tParamMeta = wf.parameterMeta.map(applyParamMeta(_, outputCtx))
    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(inputDefs, outputDefs)
    val wfType = T_Workflow(wf.name, inputType, outputType)
    TAT.Workflow(wf.name, wfType, inputDefs, outputDefs, tMeta, tParamMeta, wfElements)(wf.loc)
  }

  // Convert from AST to TAT and maintain context
  private def applyDoc(doc: AST.Document): (TAT.Document, TypeContext) = {
    val emptyCtx = TypeContext.createFromDoc(doc, regime, userDefinedFunctions, logger)

    val importDocParent = doc.source match {
      case parent: AddressableFileSource => Some(parent)
      case _                             => None
    }

    // translate each of the elements in the document
    val (elements, elementCtx) =
      doc.elements.foldLeft((Vector.empty[TAT.DocumentElement], emptyCtx)) {
        case ((tElements, beforeCtx), task: AST.Task) =>
          val tTask = applyTask(task, beforeCtx)
          val afterCtx =
            try {
              beforeCtx.bindCallable(tTask.wdlType)
            } catch {
              case e: DuplicateBindingException =>
                handleError(e.getMessage, task.loc)
                beforeCtx
            }
          (tElements :+ tTask, afterCtx)

        case ((tElements, beforeCtx), importDoc: AST.ImportDoc) =>
          // recurse into the imported document, add types
          logger.trace(s"inferring types in ${importDoc.doc.get.source}",
                       minLevel = TraceLevel.VVerbose)
          val (tDoc, importCtx) = applyDoc(importDoc.doc.get)
          val name = importDoc.name.map(_.value)
          val addr = importDoc.addr.value

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
              UUtil.changeFileExt(fileResolver.resolve(addr, importDocParent).name,
                                  dropExt = ".wdl")
            case Some(x) => x
          }

          val tAliases = importDoc.aliases.map { impAlias: AST.ImportAlias =>
            importCtx.aliases.get(impAlias.id1) match {
              case Some(referee) =>
                TAT.ImportAlias(impAlias.id1, impAlias.id2, referee)(impAlias.loc)
              case None =>
                handleError(s"missing struct ${impAlias.id1}", impAlias.loc)
                TAT.ImportAlias(impAlias.id1, impAlias.id2, T_Struct(impAlias.id1, SeqMap.empty))(
                    impAlias.loc
                )
            }
          }

          val tImportDoc = TAT.ImportDoc(namespace, tAliases, addr, tDoc)(importDoc.loc)

          // add the externally visible definitions to the context
          val afterCtx =
            try {
              beforeCtx.bindImportedDoc(namespace, importCtx, importDoc.aliases)
            } catch {
              case e: DuplicateBindingException =>
                handleError(e.getMessage, tImportDoc.loc)
                beforeCtx
            }
          (tElements :+ tImportDoc, afterCtx)

        case ((tElements, beforeCtx), struct: AST.TypeStruct) =>
          // Add the struct to the context
          val tStruct = typeFromAst(struct, struct.loc, beforeCtx).asInstanceOf[T_Struct]
          val tStructDef = TAT.StructDefinition(struct.name, tStruct, tStruct.members)(struct.loc)
          val afterCtx =
            try {
              beforeCtx.bindStruct(tStruct)
            } catch {
              case e: DuplicateBindingException =>
                handleError(e.getMessage, struct.loc)
                beforeCtx
            }
          (tElements :+ tStructDef, afterCtx)
      }

    // now that we have types for everything else, we can check the workflow
    val (workflow, finalContext) = doc.workflow match {
      case None => (None, elementCtx)
      case Some(wf) =>
        val tWorkflow = applyWorkflow(wf, elementCtx)
        val afterCtx =
          try {
            elementCtx.bindCallable(tWorkflow.wdlType)
          } catch {
            case e: DuplicateBindingException =>
              handleError(e.getMessage, wf.loc)
              elementCtx
          }
        (Some(tWorkflow), afterCtx)
    }

    val tVersion = TAT.Version(doc.version.value)(doc.version.loc)
    val tDoc = TAT.Document(doc.source, tVersion, elements, workflow, doc.comments)(doc.loc)
    (tDoc, finalContext)
  }

  // Main entry point
  //
  // check if the WDL document is correctly typed. Otherwise, throw an exception
  // describing the problem in a human readable fashion. Return a document
  // with types.
  //
  def apply(doc: AST.Document): (TAT.Document, TypeContext) = {
    //val (tDoc, _) = applyDoc(doc)
    //tDoc
    val (tDoc, ctx) = applyDoc(doc)
    if (errors.nonEmpty && errorHandler.forall(eh => eh.handleTypeErrors(errors))) {
      throw new TypeException(errors)
    }
    (tDoc, ctx)
  }

  def applyExpression(expr: AST.Expr,
                      wdlVersion: WdlVersion,
                      regime: TypeCheckingRegime,
                      docSource: FileNode,
                      bindings: Bindings[String, T] = WdlTypeBindings.empty,
                      section: Section = Section.Other): TAT.Expr = {
    val ctx = TypeContext.create(wdlVersion, docSource, regime)
    applyExpr(expr, ctx, bindings, section = section)
  }
}

object TypeInfer {
  val instance: TypeInfer = TypeInfer()
}
