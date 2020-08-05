package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}
import wdlTools.util.{FileSourceResolver, LocalFileSource, Logger}

case class Context(bindings: Map[String, WdlValues.V]) {
  def addBinding(name: String, value: WdlValues.V): Context = {
    assert(!(bindings contains name))
    this.copy(bindings = bindings + (name -> value))
  }
}

object Context {
  def createFromEnv(env: Map[String, (WdlTypes.T, WdlValues.V)]): Context = {
    Context(env.map { case (name, (_, v)) => name -> v })
  }

  lazy val empty: Context = Context(Map.empty)
}

case class Eval(paths: EvalPaths,
                wdlVersion: WdlVersion,
                fileResovler: FileSourceResolver = FileSourceResolver.get,
                logger: Logger = Logger.get) {
  // choose the standard library implementation based on version
  private val standardLibrary = Stdlib(paths, wdlVersion, fileResovler, logger)

  private def getStringVal(value: V, loc: SourceLocation): String = {
    value match {
      case V_Boolean(b) => b.toString
      case V_Int(i)     => i.toString
      case V_Float(x)   => x.toString
      case V_String(s)  => s
      case V_File(s)    => s
      case other        => throw new EvalException(s"bad value ${other}", loc)
    }
  }

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  private def exprGetName(value: V, id: String, loc: SourceLocation) = {
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

  private def apply(expr: TAT.Expr, ctx: Context): WdlValues.V = {
    expr match {
      case _: TAT.ValueNull    => V_Null
      case _: TAT.ValueNone    => V_Null
      case x: TAT.ValueBoolean => V_Boolean(x.value)
      case x: TAT.ValueInt     => V_Int(x.value)
      case x: TAT.ValueFloat   => V_Float(x.value)
      case x: TAT.ValueString  => V_String(x.value)
      case x: TAT.ValueFile    => V_File(x.value)

      // accessing a variable
      case eid: TAT.ExprIdentifier if !(ctx.bindings contains eid.id) =>
        throw new EvalException(s"accessing undefined variable ${eid.id}")
      case eid: TAT.ExprIdentifier =>
        ctx.bindings(eid.id)

      // concatenate an array of strings inside a command block
      case ecs: TAT.ExprCompoundString =>
        val strArray: Vector[String] = ecs.value.map { x =>
          val xv = apply(x, ctx)
          getStringVal(xv, x.loc)
        }
        V_String(strArray.mkString(""))

      case ep: TAT.ExprPair => V_Pair(apply(ep.l, ctx), apply(ep.r, ctx))
      case ea: TAT.ExprArray =>
        V_Array(ea.value.map { x =>
          apply(x, ctx)
        })
      case em: TAT.ExprMap =>
        V_Map(em.value.map {
          case (k, v) => apply(k, ctx) -> apply(v, ctx)
        })

      case eObj: TAT.ExprObject =>
        V_Object(eObj.value.map {
          case (k, v) =>
            // an object literal key can be a string or identifier
            val key = apply(k, ctx) match {
              case V_String(s) => s
              case _ =>
                throw new EvalException(s"bad value ${k}, object literal key must be a string",
                                        expr.loc)
            }
            key -> apply(v, ctx)
        })

      // ~{true="--yes" false="--no" boolean_value}
      case TAT.ExprPlaceholderEqual(t, f, boolExpr, _, _) =>
        apply(boolExpr, ctx) match {
          case V_Boolean(true)  => apply(t, ctx)
          case V_Boolean(false) => apply(f, ctx)
          case other =>
            throw new EvalException(s"bad value ${other}, should be a boolean", expr.loc)
        }

      // ~{default="foo" optional_value}
      case TAT.ExprPlaceholderDefault(defaultVal, optVal, _, _) =>
        apply(optVal, ctx) match {
          case V_Null => apply(defaultVal, ctx)
          case other  => other
        }

      // ~{sep=", " array_value}
      case TAT.ExprPlaceholderSep(sep: TAT.Expr, arrayVal: TAT.Expr, _, _) =>
        val sep2 = getStringVal(apply(sep, ctx), sep.loc)
        apply(arrayVal, ctx) match {
          case V_Array(ar) =>
            val elements: Vector[String] = ar.map { x =>
              getStringVal(x, expr.loc)
            }
            V_String(elements.mkString(sep2))
          case other =>
            throw new EvalException(s"bad value ${other}, should be a string", expr.loc)
        }

      // Access an array element at [index: Int] or map value at [key: K]
      case TAT.ExprAt(collection, index, _, loc) =>
        val collection_v = apply(collection, ctx)
        val index_v = apply(index, ctx)
        (collection_v, index_v) match {
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
          case (V_Map(value), key) if value.contains(key) =>
            value(key)
          case (V_Map(value), key) =>
            throw new EvalException(
                s"map ${value} does not contain key ${key}",
                loc
            )
          case _ =>
            throw new EvalException(
                s"Invalid array/map ${collection_v} and/or index ${index_v}"
            )
        }

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, loc) =>
        val cond_v = apply(cond, ctx)
        cond_v match {
          case V_Boolean(true)  => apply(tBranch, ctx)
          case V_Boolean(false) => apply(fBranch, ctx)
          case _ =>
            throw new EvalException(s"condition is not boolean", loc)
        }

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case TAT.ExprApply(funcName, _, elements, _, loc) =>
        val funcArgs = elements.map(e => apply(e, ctx))
        standardLibrary.call(funcName, funcArgs, loc)

      // Access a field in a struct or an object. For example:
      //   Int z = x.a
      //
      // shortcut. The environment has a bindings for "x.a"
      case TAT.ExprGetName(TAT.ExprIdentifier(id, _: WdlTypes.T_Call, _), fieldName, _, _)
          if ctx.bindings contains s"$id.$fieldName" =>
        ctx.bindings(s"$id.$fieldName")

      // normal path, first, evaluate the expression "x" then access field "a"
      case TAT.ExprGetName(e: TAT.Expr, fieldName, _, loc) =>
        val ev = apply(e, ctx)
        exprGetName(ev, fieldName, loc)

      case other =>
        throw new Exception(s"sanity: expression ${other} not implemented")
    }
  }

  // public entry points
  //
  def applyExpr(expr: TAT.Expr, ctx: Context): WdlValues.V = {
    apply(expr, ctx)
  }

  /**
    * Coerces the result value to the correct type.
    * For example, an expression like:
    *   Float x = "3.2"
    * requires casting from string to float
    * @param expr expression
    * @param wdlType allowed type
    * @param ctx context
    * @return
    */
  def applyExprAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T, ctx: Context): WdlValues.V = {
    val value = apply(expr, ctx)
    Coercion.coerceTo(wdlType, value, expr.loc)
  }

  def applyExprAndCoerce(expr: TAT.Expr,
                         wdlTypes: Vector[WdlTypes.T],
                         ctx: Context): WdlValues.V = {
    val value = apply(expr, ctx)
    Coercion.coerceToFirst(wdlTypes, value, expr.loc)
  }

  // Evaluate all the declarations and return a Context
  def applyDeclarations(decls: Vector[TAT.Declaration], ctx: Context): Context = {
    decls.foldLeft(ctx) {
      case (accu, TAT.Declaration(name, wdlType, Some(expr), loc)) =>
        val value = apply(expr, accu)
        val coerced = Coercion.coerceTo(wdlType, value, loc)
        accu.addBinding(name, coerced)
      case (_, ast) =>
        throw new Exception(s"Cannot evaluate element ${ast.getClass}")
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
  def applyConst(expr: TAT.Expr): WdlValues.V = {
    try {
      expr match {
        case fl: TAT.ValueFile =>
          fileResovler.resolve(fl.value) match {
            case f: LocalFileSource => throw new Exception(s"File ${f} is not constant")
            case _                  => WdlValues.V_File(fl.value)
          }
        case fl: TAT.ValueDirectory =>
          fileResovler.resolveDirectory(fl.value) match {
            case f: LocalFileSource => throw new Exception(s"Directory ${f} is not constant")
            case _                  => WdlValues.V_Directory(fl.value)
          }
        case _ =>
          apply(expr, Context.empty)
      }
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
  def applyConstAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T): WdlValues.V = {
    val value = applyConst(expr)
    Coercion.coerceTo(wdlType, value, expr.loc)
  }

  def applyConstAndCoerce(expr: TAT.Expr, wdlTypes: Vector[WdlTypes.T]): WdlValues.V = {
    val value = applyConst(expr)
    Coercion.coerceToFirst(wdlTypes, value, expr.loc)
  }

  /**
    * Given a multi-line string, determine the largest w such that each line
    * begins with at least w whitespace characters.
    * @param s the string to trim
    * @param ignoreEmptyLines ignore empty lines
    * @param lineSep character to use to separate lines in the returned String
    * @return tuple (lineOffset, colOffset, trimmedString) where lineOffset
    *  is the number of lines trimmed from the beginning of the string,
    *  colOffset is the number of whitespace characters trimmed from the
    *  beginning of the line containing the first non-whitespace character,
    *  and trimmedString is `s` with all all prefix and suffix whitespace
    *  trimmed, as well as `w` whitespace characters trimmed from the
    *  beginning of each line.
    *  @example
    *    val s = "   \n  hello\n   goodbye\n "
    *    stripLeadingWhitespace(s, false) => (1, 1, "hello\n  goodbye\n")
    *     stripLeadingWhitespace(s, true) => (1, 2, "hello\n goodbye")
    */
  private def stripLeadingWhitespace(
      s: String,
      ignoreEmptyLines: Boolean = true,
      lineSep: String = System.lineSeparator()
  ): String = {
    val lines = s.split("\r\n?|\n")
    val wsRegex = "^([ \t]*)$".r
    val nonWsRegex = "^([ \t]*)(.+)$".r
    val (_, content) = lines.foldLeft((0, Vector.empty[(String, String)])) {
      case ((lineOffset, content), wsRegex(txt)) =>
        if (content.isEmpty) {
          (lineOffset + 1, content)
        } else if (ignoreEmptyLines) {
          (lineOffset, content)
        } else {
          (lineOffset, content :+ (txt, ""))
        }
      case ((lineOffset, content), nonWsRegex(ws, txt)) => (lineOffset, content :+ (ws, txt))
    }
    if (content.isEmpty) {
      ""
    } else {
      val (whitespace, strippedLines) = content.unzip
      val colOffset = whitespace.map(_.length).min
      val strippedContent = (
          if (colOffset == 0) {
            strippedLines
          } else {
            // add back to each line any whitespace longer than colOffset
            strippedLines.zip(whitespace).map {
              case (line, ws) if ws.length > colOffset => ws.drop(colOffset) + line
              case (line, _)                           => line
            }
          }
      ).mkString(lineSep)
      strippedContent
    }
  }

  // evaluate all the parts of a command section.
  //
  def applyCommand(command: TAT.CommandSection, ctx: Context): String = {
    val commandStr = command.parts
      .map { expr =>
        val value = apply(expr, ctx)
        val str = Utils.primitiveValueToString(value, expr.loc)
        str
      }
      .mkString("")
    // strip off common leading whitespace
    stripLeadingWhitespace(commandStr)
  }
}
