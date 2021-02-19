package wdlTools.eval

import dx.util.{Bindings, EvalPaths, FileSource, FileSourceResolver, LocalFileSource, Logger}
import dx.util.CollectionUtils.IterableOnceExtensions
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.ExprState.ExprState
import wdlTools.types.{ExprState, TypeUtils, WdlTypes, TypedAbstractSyntax => TAT}

import scala.collection.immutable.TreeSeqMap

object Eval {
  lazy val empty: Eval =
    Eval(DefaultEvalPaths.empty, None, Vector.empty, FileSourceResolver.get, Logger.get)

  def createBindingsFromEnv(env: Map[String, (WdlTypes.T, V)]): Bindings[String, V] = {
    WdlValueBindings(env.map { case (name, (_, v)) => name -> v })
  }
}

case class Eval(paths: EvalPaths,
                wdlVersion: Option[WdlVersion],
                userDefinedFunctions: Vector[UserDefinedFunctionImplFactory] = Vector.empty,
                fileResovler: FileSourceResolver = FileSourceResolver.get,
                logger: Logger = Logger.get,
                allowNonstandardCoercions: Boolean = false) {
  // choose the standard library implementation based on version
  private lazy val standardLibrary = {
    wdlVersion match {
      case None =>
        throw new RuntimeException(
            "cannot evaluate standard library functions without a WDL version"
        )
      case Some(ver) => Stdlib(paths, ver, userDefinedFunctions, fileResovler, logger)
    }
  }

  /**
    * The context contains variable bindings that can be referenced when evaluating an
    * expression, and also an expression state, which determies which evaluation rules
    * apply.
    *
    * Bindings may contain fully-qualified variable names, such as `a.b.c`, where
    * `a` is a Call, Pair, or Struct. In practice, this can be used by a runtime engine
    * to simplify call inputs/outputs - e.g. if task `bar` refers to the output of task `foo`
    * (say `foo.result`), rather than having to make the `foo` call object (or some serialized
    * version thereof) avaialble to `bar`, it can just  expose an output parameter `foo.result`.
    * @param bindings variable bindings
    * @param exprState current expression state
    */
  private case class Context(bindings: Bindings[String, V] = WdlValueBindings.empty,
                             exprState: ExprState = ExprState.Start) {

    lazy val hasFullyQualifiedBindings: Boolean = bindings.keySet.exists(_.contains("."))

    def apply(name: String): V = {
      bindings
        .get(name)
        .getOrElse(
            throw new EvalException(s"accessing undefined variable ${name}")
        )
    }

    def advanceState(condition: Option[ExprState] = None): Context = {
      val newState = exprState match {
        case ExprState.Start         => ExprState.InString
        case ExprState.InString      => ExprState.InPlaceholder
        case ExprState.InPlaceholder => ExprState.InString // nested string
        case _                       => throw new Exception(s"Cannot advance state from ${exprState}")
      }
      if (condition.exists(_ < newState)) {
        throw new Exception(s"Context in an invalid state ${newState} >= ${condition.get}")
      }
      copy(exprState = newState)
    }
  }

  private object Context {
    def empty: Context = Context()
  }

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  private def exprGetName(value: V, id: String, loc: SourceLocation): V = {
    value match {
      case V_Struct(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Struct ${name} does not have member ${id}", loc)
          case Some(t) => t
        }

      case V_Object(members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Object does not have member ${id}", loc)
          case Some(t) => t
        }

      case V_Call(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Call object ${name} does not have member ${id}", loc)
          case Some(t) => t
        }

      // accessing a pair element
      case V_Pair(l, _) if id.toLowerCase() == "left"  => l
      case V_Pair(_, r) if id.toLowerCase() == "right" => r
      case V_Pair(_, _) =>
        throw new EvalException(s"accessing a pair with (${id}) is illegal", loc)

      case _ =>
        throw new EvalException(s"member access (${id}) in expression is illegal", loc)
    }
  }

  /**
    * Resolves a fully-qualified identifier.
    * @param expr the LHS expression to resolve
    * @param fieldName the RHS field name
    * @param ctx the Context
    * @return if the expression resolves to a fully-qualfied name and that name
    *         is in `ctx`, returns the bound value, otherwise None
    * @example
    * Given a declaration:
    *   `Pair[Pair[String, Int], File] foo`
    * and an expression:
    *   `foo.left.right`
    * there are two possible scenarios:
    *   1. The context contains binding `foo -> V_Pair(...)`,
    *      in which case the expression is resolved recursively by `apply`,
    *   2. The context contains binding `foo.left.right -> V_Int(...)`,
    *      in which case the entire expression needs to be resolved at once
    *      to derive the fully-qualified name to look up in the context.
    */
  private def resolveFullyQualifedIdentifier(expr: TAT.Expr,
                                             fieldName: String,
                                             ctx: Context): Option[V] = {
    // tests whether the given field name is legal for the given type
    def canResolveField(t: WdlTypes.T, field: String): Boolean = {
      TypeUtils.unwrapOptional(t) match {
        case _: WdlTypes.T_Pair if Set("left", "right").contains(field) => true
        case WdlTypes.T_Struct(_, fields) if fields.contains(field)     => true
        case WdlTypes.T_Call(_, outputs) if outputs.contains(field)     => true
        // there's no way to guarantee the resolution is valid for these types:
        case WdlTypes.T_Object => true
        case WdlTypes.T_Any    => true
        case _                 => false
      }
    }

    // builds a Vector of name components
    def inner(innerExpr: TAT.Expr, innerFieldName: String): Option[Vector[String]] = {
      innerExpr match {
        case TAT.ExprIdentifier(id, wdlType, _) if canResolveField(wdlType, innerFieldName) =>
          Some(Vector(id, innerFieldName))
        case TAT.ExprGetName(e, fieldName, _, _) =>
          inner(e, fieldName).map(v => v :+ innerFieldName)
        case _ => None
      }
    }

    // Given a Vector of name components, moving from right to left, split the
    // Vector at each index - the left-side will (when concatenated with '.'
    // separator) give a fully-qualified name that we look up in the context. If
    // it resolves to a value, then we recursively apply the field names in the
    // right side to get the final value.
    //
    // For example, say that the context contains `a.b -> Pair("x", "y")`. If we
    // try to resolve `a.b.left`, it is not in the context, so then we go to `a.b`,
    // get the value, and use it to resolve `left`, with the final result being "x".
    inner(expr, fieldName).flatMap { v =>
      v.size
        .to(1, -1)
        .iterator
        .map(v.splitAt)
        .collectFirstDefined {
          case (path, fields) =>
            ctx.bindings.get(path.mkString(".")).flatMap { value =>
              fields.foldLeft(Option(value)) {
                case (Some(v), name) =>
                  try {
                    Some(exprGetName(v, name, expr.loc))
                  } catch {
                    case _: Throwable => None
                  }
                case _ => None
              }
            }
        }
    }
  }

  // within an interpolation, null/None renders as empty string
  private def interpolationValueToString(V: V, loc: SourceLocation): String = {
    V match {
      // within an interpolation, null/None renders as empty string
      case V_Null | V_Optional(V_Null) => ""
      case other =>
        EvalUtils.formatPrimitive(other, loc)
    }
  }

  private def validateDefault(value: V, loc: SourceLocation): Unit = {
    try {
      EvalUtils.unwrapOptional(value) match {
        case V_File(path)      => fileResovler.resolve(path)
        case V_Directory(path) => fileResovler.resolveDirectory(path)
        case _                 => ()
      }
    } catch {
      case t: Throwable => throw new EvalException(t.getMessage, loc)
    }
  }

  private def apply(expr: TAT.Expr,
                    ctx: Context,
                    validate: (V, SourceLocation) => Unit = validateDefault): V = {
    def inner(nestedExpr: TAT.Expr, nestedCtx: Context): V = {
      val updatedCtx = nestedExpr match {
        case _: TAT.ValueString                             => nestedCtx
        case _ if nestedCtx.exprState == ExprState.InString =>
          // if we're in a string and the current expression is anything
          // other than ValueString, that means we're in a placeholder
          nestedCtx.advanceState()
        case _ => nestedCtx
      }
      val v = nestedExpr match {
        case _: TAT.ValueNull      => V_Null
        case _: TAT.ValueNone      => V_Null
        case x: TAT.ValueBoolean   => V_Boolean(x.value)
        case x: TAT.ValueInt       => V_Int(x.value)
        case x: TAT.ValueFloat     => V_Float(x.value)
        case x: TAT.ValueString    => V_String(x.value)
        case x: TAT.ValueFile      => V_File(x.value)
        case x: TAT.ValueDirectory => V_Directory(x.value)

        // complex types
        case TAT.ExprPair(left, right, _, _) =>
          V_Pair(inner(left, updatedCtx), inner(right, updatedCtx))
        case TAT.ExprArray(value, _, _) =>
          V_Array(value.map { x =>
            inner(x, updatedCtx)
          })
        case TAT.ExprMap(value, _, _) =>
          V_Map(value.map {
            case (k, v) => inner(k, updatedCtx) -> inner(v, updatedCtx)
          })
        case TAT.ExprObject(value, _, _) =>
          V_Object(
              value
                .map {
                  case (k, v) =>
                    // an object literal key can be a string or identifier
                    val key = inner(k, updatedCtx) match {
                      case V_String(s) => s
                      case _ =>
                        throw new EvalException(
                            s"invalid key ${k}, object literal key must be a string",
                            expr.loc
                        )
                    }
                    key -> inner(v, updatedCtx)
                }
                .to(TreeSeqMap)
          )

        case TAT.ExprIdentifier(id, _, _) if updatedCtx.bindings.contains(id) =>
          EvalUtils.unwrapOptional(updatedCtx.bindings(id))
        case TAT.ExprIdentifier(id, _, _) =>
          throw new EvalException(s"identifier ${id} not found")

        // interpolation
        case TAT.ExprCompoundString(value, _, _) =>
          // concatenate an array of strings inside an expression/command block
          val strArray: Vector[String] = value.map { expr =>
            val v = inner(expr, updatedCtx.advanceState(Some(ExprState.InString)))
            interpolationValueToString(v, expr.loc)
          }
          V_String(strArray.mkString(""))
        case TAT.ExprPlaceholderCondition(t, f, boolExpr, _, _) =>
          // ~{true="--yes" false="--no" boolean_value}
          inner(boolExpr, updatedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Boolean(true) =>
              inner(t, updatedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case V_Boolean(false) =>
              inner(f, updatedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case other =>
              throw new EvalException(
                  s"invalid boolean value ${other}, should be a boolean",
                  expr.loc
              )
          }
        case TAT.ExprPlaceholderDefault(defaultVal, optVal, _, _) =>
          // ~{default="foo" optional_value}
          inner(optVal, updatedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Null => inner(defaultVal, updatedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case other  => other
          }
        case TAT.ExprPlaceholderSep(sep: TAT.Expr, arrayVal: TAT.Expr, _, _) =>
          // ~{sep=", " array_value}
          val sepString = interpolationValueToString(
              inner(sep, updatedCtx.advanceState(Some(ExprState.InPlaceholder))),
              sep.loc
          )
          inner(arrayVal, updatedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Array(array) =>
              val elements: Vector[String] = array.map(interpolationValueToString(_, expr.loc))
              V_String(elements.mkString(sepString))
            case other =>
              throw new EvalException(s"invalid array value ${other}, should be a string", expr.loc)
          }

        case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, loc) =>
          // if (x == 1) then "Sunday" else "Weekday"
          inner(cond, updatedCtx) match {
            case V_Boolean(true)  => inner(tBranch, updatedCtx)
            case V_Boolean(false) => inner(fBranch, updatedCtx)
            case _ =>
              throw new EvalException(s"condition is not boolean", loc)
          }

        case TAT.ExprAt(collection, index, _, loc) =>
          // Access an array element at [index: Int] or map value at [key: K]
          val collectionVal = inner(collection, updatedCtx)
          val indexVal = inner(index, updatedCtx)
          (collectionVal, indexVal) match {
            case (V_Array(av), V_Int(n)) if n < av.size =>
              av(n.toInt)
            case (V_Array(av), V_Int(n)) =>
              val arraySize = av.size
              throw new EvalException(
                  s"array access out of bounds (size=${arraySize}, element accessed=${n})",
                  loc
              )
            case (_: V_Array, _) =>
              throw new EvalException(s"array access requires an array and an integer", loc)
            case (V_Map(value), key) =>
              value
                .collectFirst {
                  case (k, v) if k == key => v
                }
                .getOrElse(
                    throw new EvalException(
                        s"map ${value} does not contain key ${key}",
                        loc
                    )
                )
            case _ =>
              throw new EvalException(
                  s"Invalid array/map ${collectionVal} and/or index ${indexVal}"
              )
          }

        case TAT.ExprGetName(e: TAT.Expr, fieldName, _, loc) =>
          Option
            .when(updatedCtx.hasFullyQualifiedBindings) {
              // if the context has fully-qualified identifiers, try to
              // resolve the expression as a identifier with chained
              // field accesses
              resolveFullyQualifedIdentifier(e, fieldName, updatedCtx)
            }
            .flatten
            .getOrElse {
              // evaluate the LHS expression, then access the RHS field
              val ev = inner(e, updatedCtx)
              exprGetName(ev, fieldName, loc)
            }

        case TAT.ExprApply(funcName, _, elements, _, loc) =>
          // Apply a standard library function (including built-in operators)
          // to arguments. For example:
          //   1 + 1
          //   read_int("4")
          val funcArgs = elements.map(e => inner(e, updatedCtx))
          standardLibrary.call(funcName, funcArgs, loc, updatedCtx.exprState)

        case other =>
          throw new Exception(s"sanity: expression ${other} not implemented")
      }
      validate(v, expr.loc)
      v
    }
    inner(expr, ctx)
  }

  // public entry points
  //
  def applyExpr(expr: TAT.Expr, bindings: Bindings[String, V] = WdlValueBindings.empty): V = {
    apply(expr, Context(bindings))
  }

  /**
    * Coerces the result value to the correct type.
    * For example, an expression like:
    *   Float x = "3.2"
    * requires casting from string to float
    * @param expr expression
    * @param wdlType allowed type
    * @param bindings context
    * @return
    */
  def applyExprAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T, bindings: Bindings[String, V]): V = {
    val value = applyExpr(expr, bindings)
    Coercion.coerceTo(wdlType, value, expr.loc, allowNonstandardCoercions)
  }

  def applyExprAndCoerce(expr: TAT.Expr,
                         wdlTypes: Vector[WdlTypes.T],
                         bindings: Bindings[String, V]): V = {
    val value = applyExpr(expr, bindings)
    Coercion.coerceToFirst(wdlTypes, value, expr.loc, allowNonstandardCoercions)
  }

  // Evaluate all the declarations and return a Context
  def applyPrivateVariables(
      decls: Vector[TAT.PrivateVariable],
      bindings: Bindings[String, V] = WdlValueBindings.empty
  ): Bindings[String, V] = {
    decls.foldLeft(bindings) {
      case (accu: Bindings[String, V], TAT.PrivateVariable(name, wdlType, expr, loc)) =>
        val ctx = Context(accu)
        val value = apply(expr, ctx)
        val coerced = Coercion.coerceTo(wdlType, value, loc, allowNonstandardCoercions)
        accu.add(name, coerced)
      case (_, ast) =>
        throw new Exception(s"Cannot evaluate element ${ast.getClass}")
    }
  }

  private def validateConst(value: V, loc: SourceLocation): Unit = {
    val fs: Option[FileSource] =
      try {
        EvalUtils.unwrapOptional(value) match {
          case V_File(path)      => Some(fileResovler.resolve(path))
          case V_Directory(path) => Some(fileResovler.resolveDirectory(path))
          case _                 => None
        }
      } catch {
        case t: Throwable => throw new EvalException(t.getMessage, loc)
      }
    fs.foreach {
      case _: LocalFileSource =>
        throw new EvalException(s"Path ${fs} is not constant", loc)
      case _ => ()
    }
  }

  /**
    * Evaluates a constant expression. Throws an exception if it isn't a constant.
    * @param expr the expression to evaluate
    * @return the value
    * @example
    * task foo {
    *   command {
    *       # create file mut.vcf
    *   }
    *   output {
    *     File mutations = "mut.vcf"
    *   }
    * }
    *
    * File 'mutations' is not a constant output. It is generated,
    * read from disk, and uploaded to the platform.  A file can't
    * have a constant string as an input, this has to be a dnanexus
    * link.
    */
  def applyConst(expr: TAT.Expr): V = {
    try {
      apply(expr, Context.empty, validateConst)
    } catch {
      case t: Throwable =>
        throw new EvalException(
            s"Expression cannot be evaluated without the runtime context: ${t.getMessage}",
            expr.loc
        )
    }
  }

  /**
    * Coerces the result value to the correct type.
    * For example, an expression like:
    *   Float x = "3.2"
    * requires casting from string to float
    * @param expr expression
    * @param wdlType allowed type
    * @return
    */
  def applyConstAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T): V = {
    val value = applyConst(expr)
    val coerced = Coercion.coerceTo(wdlType, value, expr.loc, allowNonstandardCoercions)
    validateConst(coerced, expr.loc)
    coerced
  }

  def applyConstAndCoerce(expr: TAT.Expr, wdlTypes: Vector[WdlTypes.T]): V = {
    val value = applyConst(expr)
    val coerced = Coercion.coerceToFirst(wdlTypes, value, expr.loc, allowNonstandardCoercions)
    validateConst(coerced, expr.loc)
    coerced
  }

  /**
    * Determines if `expr` is a constant expression - i.e. it can be
    * evaluated without any Context.
    * @param expr the expression
    * @return
    */
  def isConst(expr: TAT.Expr): Boolean = {
    try {
      applyConst(expr)
      true
    } catch {
      case _: EvalException => false
    }
  }

  /**
    * Determines if `expr` is a constant expression - i.e. it can be
    * evaluated without any Context - and is coercible to `wdlType`
    * @param expr the expression
    * @param wdlType the type requirement
    * @return
    */
  def isConst(expr: TAT.Expr, wdlType: WdlTypes.T): Boolean = {
    try {
      applyConstAndCoerce(expr, wdlType)
      true
    } catch {
      case _: EvalException => false
    }
  }

  /**
    * Given a multi-line string, determine the largest w such that each line
    * begins with at least w whitespace characters. Trailing whitespace is
    * stripped off prior to determining common whitespace.
    * @param s the string to trim
    * @return tuple (lineOffset, colOffset, trimmedString) where lineOffset
    *  is the number of lines trimmed from the beginning of the string,
    *  colOffset is the number of whitespace characters trimmed from the
    *  beginning of the line containing the first non-whitespace character,
    *  and trimmedString is `s` with all all prefix and suffix whitespace
    *  trimmed, as well as `w` whitespace characters trimmed from the
    *  beginning of each line.
    *  @example
    *    val s = "   \n  hello\n   goodbye\n "
    *    stripLeadingWhitespace(s) => (1, 2, "hello\n goodbye")
    */
  private def stripLeadingWhitespace(s: String): String = {
    // split string into lines and drop all leading and trailing empty lines
    val lines =
      s.split("\r\n?|\n").dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val wsRegex = "^([ \t]*)$".r
    val nonWsRegex = "^([ \t]*)(.+)$".r
    val content = lines.foldLeft(Vector.empty[(String, String)]) {
      case (content, wsRegex(txt))        => content :+ (txt, "")
      case (content, nonWsRegex(ws, txt)) => content :+ (ws, txt)
    }
    if (content.isEmpty) {
      ""
    } else {
      val (whitespace, strippedLines) = content.unzip
      val colOffset = whitespace.map(_.length).min
      val strippedContent = if (colOffset == 0) {
        strippedLines
      } else {
        // add back to each line any whitespace longer than colOffset
        strippedLines.zip(whitespace).map {
          case (line, ws) if ws.length > colOffset => ws.drop(colOffset) + line
          case (line, _)                           => line
        }
      }
      strippedContent.mkString(System.lineSeparator())
    }
  }

  // evaluate all the parts of a command section.
  //
  def applyCommand(command: TAT.CommandSection,
                   bindings: Bindings[String, V] = WdlValueBindings.empty): String = {
    val ctx = Context(bindings, ExprState.InString)
    val commandStr = command.parts
      .map { expr =>
        apply(expr, ctx) match {
          case V_Null => ""
          case value  => EvalUtils.formatPrimitive(value, expr.loc)
        }
      }
      .mkString("")
    // strip off common leading whitespace
    stripLeadingWhitespace(commandStr)
  }
}
