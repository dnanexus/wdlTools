package wdlTools.generators.code

import wdlTools.generators.code.WdlGenerator._
import wdlTools.generators.code.Indenting.Indenting
import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.types.{
  ExprState,
  Stdlib,
  TypeCheckingRegime,
  TypeUtils,
  Unification,
  UnificationContext
}
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes.{T_Int, T_Object, T_String, _}
import wdlTools.syntax.{Builtins, Operator, Quoting, SourceLocation, WdlVersion}

import java.net.URI
import scala.collection.mutable

object WdlGenerator {
  trait Composite extends Sized {

    /**
      * Format the contents of the composite. The `lineGenerator` passed to this method
      * must have `isLineBegun == true` on both entry and exit.
      *
      * @param lineGenerator the LineGenerator
      */
    def generateContents(lineGenerator: LineGenerator): Unit
  }

  class LineGenerator(
      indenting: Indenting = Indenting.IfNotIndented,
      indentStep: Int = 2,
      initialIndentSteps: Int = 0,
      indentation: String = " ",
      wrapping: Wrapping = Wrapping.AsNeeded,
      maxLineWidth: Int = 100,
      private val lines: mutable.Buffer[String],
      private val currentLine: mutable.StringBuilder,
      private var currentIndentSteps: Int = 0,
      private var currentSpacing: Spacing = Spacing.On,
      private val lineBegun: MutableHolder[Boolean] = MutableHolder[Boolean](false),
      private val skipNextSpace: MutableHolder[Boolean] = MutableHolder[Boolean](false)
  ) {

    /**
      * Derive a new LineFormatter with the current state modified by the specified parameters.
      *
      * @param increaseIndent whether to incerase the indent by one step
      * @param newIndenting new value for `indenting`
      * @param newSpacing new value for `spacing`
      * @param newWrapping new value for `wrapping`
      * @return
      */
    def derive(increaseIndent: Boolean = false,
               newIndentSteps: Option[Int] = None,
               newIndenting: Indenting = indenting,
               newSpacing: Spacing = currentSpacing,
               newWrapping: Wrapping = wrapping): LineGenerator = {
      val newInitialIndentSteps =
        newIndentSteps.getOrElse(initialIndentSteps + (if (increaseIndent) 1 else 0))
      val newCurrentIndentSteps = Math.max(currentIndentSteps, newInitialIndentSteps)
      new LineGenerator(newIndenting,
                        indentStep,
                        newInitialIndentSteps,
                        indentation,
                        newWrapping,
                        maxLineWidth,
                        lines,
                        currentLine,
                        newCurrentIndentSteps,
                        newSpacing,
                        lineBegun,
                        skipNextSpace)
    }

    def isLineBegun: Boolean = lineBegun.value

    def atLineStart: Boolean = {
      currentLine.length <= (currentIndentSteps * indentStep)
    }

    def getIndentSteps(changeSteps: Int = 0): Int = {
      currentIndentSteps + changeSteps
    }

    def getIndent(changeSteps: Int = 0): String = {
      indentation * (getIndentSteps(changeSteps) * indentStep)
    }

    def lengthRemaining: Int = {
      maxLineWidth - Math.max(currentLine.length, currentIndentSteps * indentStep)
    }

    def emptyLine(): Unit = {
      require(!isLineBegun)
      lines.append("")
    }

    def beginLine(): Unit = {
      require(!isLineBegun)
      currentLine.append(getIndent())
      lineBegun.value = true
    }

    private def dent(indenting: Indenting): Unit = {
      indenting match {
        case Indenting.Always =>
          currentIndentSteps += 1
        case Indenting.IfNotIndented if currentIndentSteps == initialIndentSteps =>
          currentIndentSteps += 1
        case Indenting.Dedent if currentIndentSteps > initialIndentSteps =>
          currentIndentSteps -= 1
        case Indenting.Reset =>
          currentIndentSteps = initialIndentSteps
        case Indenting.Never =>
          currentIndentSteps = 0
        case _ => ()
      }
    }

    def endLine(continue: Boolean = false): Unit = {
      require(isLineBegun)
      if (!atLineStart) {
        // the line could have trailing whitespace, such as from a comment or
        // when a space was added prior to a line-wrap - trim it off
        lines.append(currentLine.toString.replaceAll("""(?m)[ \t]+$""", ""))
        if (continue) {
          dent(indenting)
        } else {
          dent(Indenting.Reset)
        }
      }
      currentLine.clear()
      lineBegun.value = false
      skipNextSpace.value = false
    }

    /**
      * Append a single `sized`.
      * @param sized the `sized` to append
      * @param continue whether to continue the current indenting
      * @example
      * # continue = true
      * Int i = 1 +
      *   (2 * 3) -
      *   (4 / 5)
      * # continue = false
      * {
      *   x: 1,
      *   y: 2
      * }
      */
    def append(sized: Sized, continue: Boolean = true): Unit = {
      require(isLineBegun)
      if (wrapping == Wrapping.Always) {
        endLine(continue = continue)
        beginLine()
      } else {
        val addSpace = currentLine.nonEmpty &&
          currentSpacing == Spacing.On &&
          !skipNextSpace.value &&
          !currentLine.last.isWhitespace &&
          currentLine.last != indentation.last
        if (wrapping != Wrapping.Never && lengthRemaining < (
                sized.firstLineLength + (if (addSpace) 1 else 0)
            )) {
          endLine(continue = continue)
          beginLine()
        } else if (addSpace) {
          currentLine.append(" ")
        }
      }
      sized match {
        case c: Composite =>
          c.generateContents(this)
        case a =>
          currentLine.append(a.toString)
          if (skipNextSpace.value) {
            skipNextSpace.value = false
          }
      }
    }

    def appendAll(sizeds: Vector[Sized], continue: Boolean = true): Unit = {
      sizeds.foreach(sized => append(sized, continue))
    }

    // TODO: these two methods are a hack - they are currently needed to handle the case of
    //  printing a prefix followed by any number of spans followed by a suffix, and suppress
    //  the space after the prefix and before the suffix. Ideally, this would be handled by
    //  `append` using a different `Spacing` value.

    def appendPrefix(prefix: Sized): Unit = {
      append(prefix)
      skipNextSpace.value = true
    }

    def appendSuffix(suffix: Sized): Unit = {
      skipNextSpace.value = true
      append(suffix)
    }

    def toVector: Vector[String] = {
      lines.toVector
    }
  }

  object LineGenerator {
    def apply(indenting: Indenting = Indenting.IfNotIndented,
              indentStep: Int = 2,
              initialIndentSteps: Int = 0,
              indentation: String = " ",
              wrapping: Wrapping = Wrapping.AsNeeded,
              maxLineWidth: Int = 100): LineGenerator = {
      val lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty
      val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
      new LineGenerator(indenting,
                        indentStep,
                        initialIndentSteps,
                        indentation,
                        wrapping,
                        maxLineWidth,
                        lines,
                        currentLine)
    }
  }
}

/**
  * Generates WDL source code from a TypedAbstractSyntax document.
  * @param targetVersion WDL version to generate
  * @param omitNullCallInputs whether to omit any call inputs that can be
  *                           determined statically to be null
  * @param rewriteNonstandardUsages whether to check for and re-write common
  *                                 non-standard WDL usages. This is intended
  *                                 to fix usages that are only allowed by
  *                                 setting the type-checking regime to "lenient",
  *                                 such that the generated WDL could be successfully
  *                                 re-checked under "moderate".
  */
case class WdlGenerator(targetVersion: Option[WdlVersion] = None,
                        omitNullCallInputs: Boolean = true,
                        rewriteNonstandardUsages: Boolean = false) {
  if (targetVersion.exists(_ < WdlVersion.V1)) {
    throw new Exception(s"WDL version ${targetVersion.get} is not supported")
  }

  private case class Literal(value: Any, quoting: Quoting.Quoting = Quoting.None) extends Sized {
    override lazy val length: Int = toString.length

    override lazy val toString: String = {
      quoting match {
        case Quoting.Single => s"'${value}'"
        case Quoting.Double => s"${'"'}${value}${'"'}"
        case _              => value.toString
      }
    }
  }

  private case class Sequence(sizeds: Vector[Sized],
                              wrapping: Wrapping = Wrapping.Never,
                              spacing: Spacing = Spacing.Off,
                              continue: Boolean = true)
      extends Composite {
    require(sizeds.nonEmpty)

    override lazy val length: Int = {
      sizeds.map(_.length).sum + (if (spacing == Spacing.On) sizeds.length else 0)
    }

    override lazy val firstLineLength: Int = {
      if (wrapping == Wrapping.Never || wrapping == Wrapping.AllOrNone) {
        length
      } else {
        sizeds.head.firstLineLength
      }
    }

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      val contentGenerator = if (wrapping == Wrapping.AllOrNone) {
        // inherit the lineGenerator's wrapping
        lineGenerator.derive(newSpacing = spacing)
      } else {
        // override the lineGenerator's wrapping
        lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping)
      }
      contentGenerator.appendAll(sizeds, continue)
    }
  }

  private abstract class Group(ends: Option[(Sized, Sized)] = None,
                               val wrapping: Wrapping = Wrapping.Never,
                               val spacing: Spacing = Spacing.On,
                               val continue: Boolean = false)
      extends Composite {

    private val endLengths: (Int, Int) =
      ends.map(e => (e._1.length, e._2.length)).getOrElse((0, 0))

    override lazy val length: Int = body.map(_.length).getOrElse(0) + endLengths._1 + endLengths._2

    override lazy val firstLineLength: Int = {
      if (wrapping == Wrapping.Never || wrapping == Wrapping.AllOrNone || body.isEmpty) {
        length
      } else if (ends.isDefined) {
        ends.get._1.length
      } else {
        body.get.firstLineLength
      }
    }

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      if (ends.isDefined) {
        val (prefix, suffix) = ends.get
        if (body.nonEmpty && (
                wrapping == Wrapping.Always || (
                    wrapping != Wrapping.Never && length > lineGenerator.lengthRemaining
                )
            )) {
          val bodyIndent = lineGenerator.getIndentSteps(1)

          lineGenerator.append(prefix)
          lineGenerator.endLine(continue = continue)

          val effectiveWrapping = if (wrapping == Wrapping.AllOrNone) Wrapping.Always else wrapping
          val bodyGenerator = lineGenerator
            .derive(newIndentSteps = Some(bodyIndent),
                    newSpacing = Spacing.On,
                    newWrapping = effectiveWrapping)
          bodyGenerator.beginLine()
          bodyGenerator.append(body.get)
          bodyGenerator.endLine()

          lineGenerator.beginLine()
          lineGenerator.append(suffix)
        } else {
          val effectiveWrapping = if (wrapping == Wrapping.AllOrNone) Wrapping.Never else wrapping
          val adjacentGenerator =
            lineGenerator.derive(newSpacing = spacing, newWrapping = effectiveWrapping)
          adjacentGenerator.appendPrefix(prefix)
          if (body.nonEmpty) {
            adjacentGenerator.append(body.get)
          }
          adjacentGenerator.appendSuffix(suffix)
        }
      } else if (body.isDefined) {
        lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping).append(body.get)
      }
    }

    def body: Option[Composite]
  }

  private case class Container(items: Vector[Sized],
                               delimiter: Option[String] = None,
                               ends: Option[(Sized, Sized)] = None,
                               override val wrapping: Wrapping = Wrapping.AsNeeded,
                               override val continue: Boolean = true)
      extends Group(ends = ends, wrapping = wrapping, continue = continue) {

    override lazy val body: Option[Composite] = if (items.nonEmpty) {
      Some(
          Sequence(
              items.zipWithIndex.map {
                case (item, i) if i < items.size - 1 =>
                  if (delimiter.isDefined) {
                    val delimiterLiteral = Literal(delimiter.get)
                    Sequence(Vector(item, delimiterLiteral))
                  } else {
                    item
                  }
                case (item, _) => item
              },
              wrapping = wrapping,
              spacing = Spacing.On,
              continue = continue
          )
      )
    } else {
      None
    }
  }

  private case class KeyValue(key: Sized,
                              value: Sized,
                              delimiter: String = Symbols.KeyValueDelimiter)
      extends Composite {
    private val delimiterLiteral: Literal = Literal(delimiter)

    override lazy val firstLineLength: Int = key.length + delimiterLiteral.length

    override lazy val length: Int = firstLineLength + value.length + 1

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newWrapping = Wrapping.Never, newSpacing = Spacing.On)
        .appendAll(Vector(Sequence(Vector(key, delimiterLiteral)), value))
    }
  }

  private object DataType {
    def buildDataType(name: String,
                      quantifiers: Vector[Sized],
                      inner1: Option[Sized] = None,
                      inner2: Option[Sized] = None): Sized = {
      val nameLiteral: Literal = Literal(name)
      if (inner1.isDefined) {
        val openLiteral = Literal(Symbols.TypeParamOpen)
        val prefix = Sequence(Vector(nameLiteral, openLiteral))
        val suffix = if (quantifiers.nonEmpty) {
          Sequence(Vector(Literal(Symbols.TypeParamClose)) ++ quantifiers)
        } else {
          Literal(Symbols.TypeParamClose)
        }
        Container(
            Vector(inner1, inner2).flatten,
            Some(Symbols.ArrayDelimiter),
            Some((prefix, suffix))
        )
      } else if (quantifiers.nonEmpty) {
        Sequence(Vector(nameLiteral) ++ quantifiers)
      } else {
        nameLiteral
      }
    }

    private def isPrimitiveType(wdlType: T): Boolean = {
      wdlType match {
        case T_String    => true
        case T_Boolean   => true
        case T_Int       => true
        case T_Float     => true
        case T_File      => true
        case T_Directory => true
        case _           => false
      }
    }

    def fromWdlType(wdlType: T, quantifiers: Vector[Sized] = Vector.empty): Sized = {
      wdlType match {
        case T_Optional(inner) =>
          fromWdlType(inner, quantifiers = Vector(Literal(Symbols.Optional)))
        case T_String    => buildDataType(Symbols.StringType, quantifiers)
        case T_Boolean   => buildDataType(Symbols.BooleanType, quantifiers)
        case T_Int       => buildDataType(Symbols.IntType, quantifiers)
        case T_Float     => buildDataType(Symbols.FloatType, quantifiers)
        case T_File      => buildDataType(Symbols.FileType, quantifiers)
        case T_Directory => buildDataType(Symbols.DirectoryType, quantifiers)
        case T_Array(inner, nonEmpty) =>
          val quant = if (nonEmpty) {
            Vector(Literal(Symbols.NonEmpty))
          } else {
            Vector.empty
          }
          buildDataType(Symbols.ArrayType, quant ++ quantifiers, Some(fromWdlType(inner)))
        case T_Map(keyType, valueType) if isPrimitiveType(keyType) =>
          buildDataType(Symbols.MapType,
                        quantifiers,
                        Some(fromWdlType(keyType)),
                        Some(fromWdlType(valueType)))
        case T_Pair(left, right) =>
          buildDataType(Symbols.PairType,
                        quantifiers,
                        Some(fromWdlType(left)),
                        Some(fromWdlType(right)))
        case T_Object          => buildDataType(Symbols.ObjectType, quantifiers)
        case T_Struct(name, _) => buildDataType(name, quantifiers)
        case other             => throw new Exception(s"Unrecognized type $other")
      }
    }
  }

  private case class Operation(oper: String,
                               operands: Vector[Sized],
                               vectorizable: Boolean,
                               ctx: ExpressionContext)
      extends Group(ends = if (ctx.groupOperation(oper)) {
        Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose))
      } else {
        None
      }, wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AsNeeded)
      with Composite {

    override lazy val body: Option[Composite] = {
      val symbolTable = if (vectorizable) Operator.Vectorizable else Operator.All
      val operLiteral = Literal(symbolTable(oper).symbol)
      val seq: Vector[Sized] = operands.head +: Iterator
        .continually(operLiteral)
        .zip(operands.tail)
        .flatten { case (a, b) => Vector(a, b) }
        .toVector
      Some(Sequence(seq, wrapping = wrapping, spacing = Spacing.On))
    }
  }

  private case class Placeholder(value: Sized,
                                 open: String,
                                 inString: Boolean,
                                 options: Option[Vector[Sized]] = None)
      extends Group(
          ends = Some(Literal(open), Literal(Symbols.PlaceholderClose)),
          wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded,
          spacing = if (inString) Spacing.Off else Spacing.On
      )
      with Composite {

    override lazy val body: Option[Composite] = Some(
        Sequence(
            options.getOrElse(Vector.empty) ++ Vector(value),
            wrapping = wrapping,
            spacing = Spacing.On
        )
    )
  }

  private case class CompoundString(sizeds: Vector[Sized], quoting: Quoting.Quoting)
      extends Composite {
    override lazy val length: Int = sizeds
      .map(_.length)
      .sum + (if (quoting == Quoting.None) 0 else 2)

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      val unspacedFormatter =
        lineGenerator.derive(newWrapping = Wrapping.Never, newSpacing = Spacing.Off)
      if (quoting == Quoting.None) {
        unspacedFormatter.appendAll(sizeds)
      } else {
        val (open, close) = quoting match {
          case Quoting.Single => (Symbols.SingleQuoteOpen, Symbols.SingleQuoteClose)
          case Quoting.Double => (Symbols.DoubleQuoteOpen, Symbols.DoubleQuoteClose)
          case _              => throw new Exception("unreachable")
        }
        unspacedFormatter.appendPrefix(Literal(open))
        unspacedFormatter.appendAll(sizeds)
        unspacedFormatter.appendSuffix(Literal(close))
      }
    }
  }

  // fix expressions with non-standard WDL usages
  private def fixExpression(expr: Expr,
                            ctx: ExpressionContext = ExpressionContext.default): Expr = {
    def isMixedStringAndNonString(exprs: Vector[Expr], inPlaceholder: Boolean): Boolean = {
      val unify = Unification(TypeCheckingRegime.Moderate)
      val unifyCtx = UnificationContext(targetVersion, inPlaceholder = inPlaceholder)
      exprs.map(e => unify.isCoercibleTo(T_String, e.wdlType, unifyCtx)).toSet.size == 2
    }
    def inner(innerExpr: Expr, innerCtx: ExpressionContext): Expr = {
      innerExpr match {
        case p @ ExprPlaceholder(t, f, sep, default, value, wdlType) =>
          val newCtx = innerCtx.advanceTo(InPlaceholderState)
          val fixedValue = inner(value, newCtx)
          val newValue: Expr =
            if (default.isEmpty &&
                (t.isDefined || f.isDefined || sep.isDefined) &&
                TypeUtils.isOptional(fixedValue.wdlType)) {
              // wrap optional placeholder value in select_first if there is
              // a sep or true/false option without a default option
              val stdlib = Stdlib(TypeCheckingRegime.Moderate, targetVersion.get)
              val arrayWdlType = T_Array(fixedValue.wdlType)
              val (selectFirstType, selectFirstProto) = stdlib.apply(
                  Builtins.SelectFirst,
                  Vector(arrayWdlType),
                  ExprState.InPlaceholder
              )
              val newValue =
                ExprApply(Builtins.SelectFirst,
                          selectFirstProto,
                          Vector(ExprArray(Vector(fixedValue), arrayWdlType)(fixedValue.loc)),
                          selectFirstType)(
                    fixedValue.loc
                )
              newValue
            } else {
              fixedValue
            }
          ExprPlaceholder(t.map(inner(_, newCtx)),
                          f.map(inner(_, newCtx)),
                          sep.map(inner(_, newCtx)),
                          default.map(inner(_, newCtx)),
                          newValue,
                          wdlType)(p.loc)
        case ExprApply(Operator.Addition.name, _, Vector(ExprArray(args, _)), _)
            if isMixedStringAndNonString(args, innerCtx.inPlaceholder) =>
          // nonstandard usage: "foo " + bar + " baz", where bar is a non-String type
          // re-write as "foo ${bar} baz"
          val (newArgs, quotings) = args.map {
            case s @ ValueString(_, _, quoting) if quoting != Quoting.None =>
              (s.copy(quoting = Quoting.None)(s.loc), Some(quoting))
            case s @ ExprCompoundString(_, _, quoting) if quoting != Quoting.None =>
              (s.copy(quoting = Quoting.None)(s.loc), Some(quoting))
            case arg => (arg, None)
          }.unzip
          val quoting = quotings.flatten.distinct match {
            case Vector()        => Quoting.Double
            case Vector(quoting) => quoting
            case _ =>
              throw new Exception(
                  s"Cannot re-write non-standard expression ${expr}: sub-expressions use different quoting"
              )
          }
          val loc = SourceLocation.merge(newArgs.map(_.loc))
          val strCtx = innerCtx.advanceTo(InStringState(quoting))
          inner(ExprCompoundString(newArgs, T_String, quoting)(loc), strCtx)

        // remaining expression types don't need fixing but we need to recurse to fix nested expressions
        case a @ ExprArray(value, wdlType) =>
          ExprArray(value.map(inner(_, innerCtx)), wdlType)(a.loc)
        case p @ ExprPair(left, right, wdlType) =>
          ExprPair(inner(left, innerCtx), inner(right, innerCtx), wdlType)(p.loc)
        case m @ ExprMap(value, wdlType) =>
          ExprMap(value.map {
            case (k, v) => (inner(k, innerCtx), inner(v, innerCtx))
          }, wdlType)(m.loc)
        case o @ ExprObject(value, wdlType) =>
          ExprObject(value.map {
            case (k, v) => k -> inner(v, innerCtx)
          }, wdlType)(o.loc)
        case s @ ExprCompoundString(value, wdlType, quoting) =>
          ExprCompoundString(value.map(inner(_, innerCtx)), wdlType, quoting)(s.loc)
        case _ =>
          val nextCtx = if (innerCtx.inString(resetInPlaceholder = true)) {
            innerCtx.advanceTo(InPlaceholderState)
          } else {
            innerCtx
          }
          innerExpr match {
            case a @ ExprAt(array, index, wdlType) =>
              ExprAt(inner(array, nextCtx), inner(index, nextCtx), wdlType)(a.loc)
            case i @ ExprIfThenElse(cond, tBranch, fBranch, wdlType) =>
              ExprIfThenElse(inner(cond, nextCtx),
                             inner(tBranch, nextCtx),
                             inner(fBranch, nextCtx),
                             wdlType)(i.loc)
            case a @ ExprApply(oper, sig, elements, wdlType) if Operator.All.contains(oper) =>
              val operCtx = nextCtx.advanceTo(InOperationState(Some(oper)))
              ExprApply(oper, sig, elements.map(inner(_, operCtx)), wdlType)(a.loc)
            case a @ ExprApply(oper, sig, elements, wdlType) =>
              ExprApply(oper, sig, elements.map(inner(_, nextCtx)), wdlType)(a.loc)
            case g @ ExprGetName(e, id, wdlType) =>
              ExprGetName(inner(e, nextCtx), id, wdlType)(g.loc)
            case _ => innerExpr
          }
      }
    }
    if (rewriteNonstandardUsages) {
      inner(expr, ctx)
    } else {
      expr
    }
  }

  // fix expressions with non-standard WDL usages
  private def fixExpression(expr: Expr,
                            ctx: ExpressionContext = ExpressionContext.default): Expr = {
    def isMixedStringAndNonString(exprs: Vector[Expr], inPlaceholder: Boolean): Boolean = {
      val unify = Unification(TypeCheckingRegime.Moderate)
      val unifyCtx = UnificationContext(targetVersion, inPlaceholder = inPlaceholder)
      exprs.map(e => unify.isCoercibleTo(T_String, e.wdlType, unifyCtx)).toSet.size == 2
    }
    def inner(innerExpr: Expr, innerCtx: ExpressionContext): Expr = {
      innerExpr match {
        case p @ ExprPlaceholder(t, f, sep, default, value, wdlType) =>
          val newCtx = innerCtx.advanceTo(InPlaceholderState)
          val fixedValue = inner(value, newCtx)
          val newValue: Expr =
            if (default.isEmpty &&
                (t.isDefined || f.isDefined || sep.isDefined) &&
                TypeUtils.isOptional(fixedValue.wdlType)) {
              // wrap optional placeholder value in select_first if there is
              // a sep or true/false option without a default option
              val stdlib = Stdlib(TypeCheckingRegime.Moderate, targetVersion.get)
              val arrayWdlType = T_Array(fixedValue.wdlType)
              val (selectFirstType, selectFirstProto) = stdlib.apply(
                  Builtins.SelectFirst,
                  Vector(arrayWdlType),
                  ExprState.InPlaceholder
              )
              val newValue =
                ExprApply(Builtins.SelectFirst,
                          selectFirstProto,
                          Vector(ExprArray(Vector(fixedValue), arrayWdlType)(fixedValue.loc)),
                          selectFirstType)(
                    fixedValue.loc
                )
              newValue
            } else {
              fixedValue
            }
          ExprPlaceholder(t.map(inner(_, newCtx)),
                          f.map(inner(_, newCtx)),
                          sep.map(inner(_, newCtx)),
                          default.map(inner(_, newCtx)),
                          newValue,
                          wdlType)(p.loc)
        case ExprApply(Operator.Addition.name, _, Vector(ExprArray(args, _)), _)
            if isMixedStringAndNonString(args, innerCtx.inPlaceholder) =>
          // nonstandard usage: "foo " + bar + " baz", where bar is a non-String type
          // re-write as "foo ${bar} baz"
          val (newArgs, quotings) = args.map {
            case s @ ValueString(_, _, quoting) if quoting != Quoting.None =>
              (s.copy(quoting = Quoting.None)(s.loc), Some(quoting))
            case s @ ExprCompoundString(_, _, quoting) if quoting != Quoting.None =>
              (s.copy(quoting = Quoting.None)(s.loc), Some(quoting))
            case arg => (arg, None)
          }.unzip
          val quoting = quotings.flatten.distinct match {
            case Vector()        => Quoting.Double
            case Vector(quoting) => quoting
            case _ =>
              throw new Exception(
                  s"Cannot re-write non-standard expression ${expr}: sub-expressions use different quoting"
              )
          }
          val loc = SourceLocation.merge(newArgs.map(_.loc))
          val strCtx = innerCtx.advanceTo(InStringState(quoting))
          inner(ExprCompoundString(newArgs, T_String, quoting)(loc), strCtx)

        // remaining expression types don't need fixing but we need to recurse to fix nested expressions
        case a @ ExprArray(value, wdlType) =>
          ExprArray(value.map(inner(_, innerCtx)), wdlType)(a.loc)
        case p @ ExprPair(left, right, wdlType) =>
          ExprPair(inner(left, innerCtx), inner(right, innerCtx), wdlType)(p.loc)
        case m @ ExprMap(value, wdlType) =>
          ExprMap(value.map {
            case (k, v) => (inner(k, innerCtx), inner(v, innerCtx))
          }, wdlType)(m.loc)
        case o @ ExprObject(value, wdlType) =>
          ExprObject(value.map {
            case (k, v) => k -> inner(v, innerCtx)
          }, wdlType)(o.loc)
        case s @ ExprCompoundString(value, wdlType, quoting) =>
          ExprCompoundString(value.map(inner(_, innerCtx)), wdlType, quoting)(s.loc)
        case _ =>
          val nextCtx = if (innerCtx.inString(resetInPlaceholder = true)) {
            innerCtx.advanceTo(InPlaceholderState)
          } else {
            innerCtx
          }
          innerExpr match {
            case a @ ExprAt(array, index, wdlType) =>
              ExprAt(inner(array, nextCtx), inner(index, nextCtx), wdlType)(a.loc)
            case i @ ExprIfThenElse(cond, tBranch, fBranch, wdlType) =>
              ExprIfThenElse(inner(cond, nextCtx),
                             inner(tBranch, nextCtx),
                             inner(fBranch, nextCtx),
                             wdlType)(i.loc)
            case a @ ExprApply(oper, sig, elements, wdlType) if Operator.All.contains(oper) =>
              val operCtx = nextCtx.advanceTo(InOperationState(Some(oper)))
              ExprApply(oper, sig, elements.map(inner(_, operCtx)), wdlType)(a.loc)
            case a @ ExprApply(oper, sig, elements, wdlType) =>
              ExprApply(oper, sig, elements.map(inner(_, nextCtx)), wdlType)(a.loc)
            case g @ ExprGetName(e, id, wdlType) =>
              ExprGetName(inner(e, nextCtx), id, wdlType)(g.loc)
            case _ => innerExpr
          }
      }
    }
    if (rewriteNonstandardUsages) {
      inner(expr, ctx)
    } else {
      expr
    }
  }

  private def buildExpression(
      expr: Expr,
      ctx: ExpressionContext = ExpressionContext.default
  ): Sized = {

    def string(value: String, quoting: Quoting.Quoting = Quoting.Double): Literal = {
      val escaped =
        if (quoting != Quoting.None || ctx.inString(quoted = true, resetInPlaceholder = true)) {
          Utils.escape(value)
        } else {
          value
        }
      Literal(escaped, quoting = quoting)
    }

    def placeholderOption(name: String, value: Expr): Sized = {
      val nameLiteral = Literal(name)
      val eqLiteral = Literal(Symbols.Assignment)
      val exprSized = buildExpression(value, ctx.advanceTo(InPlaceholderState))
      Sequence(Vector(nameLiteral, eqLiteral, exprSized))
    }

    expr match {
      // literal values
      case ValueNone(_)                   => Literal(Symbols.None)
      case ValueString(value, _, quoting) => string(value, quoting)
      case ValueFile(value, _)            => string(value)
      case ValueDirectory(value, _)       => string(value)
      case ValueBoolean(value, _)         => Literal(value)
      case ValueInt(value, _)             => Literal(value)
      case ValueFloat(value, _)           => Literal(value)
      case ExprArray(value, _) =>
        Container(
            value.map(buildExpression(_, ctx)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose)),
            wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AllOrNone
        )
      case ExprPair(left, right, _) =>
        Container(
            Vector(buildExpression(left, ctx), buildExpression(right, ctx)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose)),
            wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AsNeeded
        )
      case ExprMap(value, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(buildExpression(k, ctx), buildExpression(v, ctx))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.MapOpen), Literal(Symbols.MapClose)),
            wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.Always,
            continue = false
        )
      case ExprObject(value, wdlType) =>
        val name = wdlType match {
          case T_Struct(name, _) if targetVersion.exists(_ >= WdlVersion.V2) =>
            name
          case _: T_Struct | T_Object =>
            Symbols.Object
          case _ =>
            throw new Exception(s"unexpected object wdlType ${wdlType}")
        }
        Container(
            value.map {
              case (ValueString(k, _, _), v) =>
                KeyValue(Literal(k), buildExpression(v, ctx))
              case other =>
                throw new Exception(s"invalid object member ${other}")
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Sequence(Vector(Literal(name), Literal(Symbols.ObjectOpen)), spacing = Spacing.On),
                 Literal(Symbols.ObjectClose)),
            wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.Always,
            continue = false
        )
      // placeholders
      case ExprPlaceholder(t, f, sep, default, value, _) =>
        Placeholder(
            buildExpression(value, ctx.advanceTo(InPlaceholderState)),
            ctx.placeholderOpen,
            ctx.inString(),
            Some(
                Vector(
                    t.map(e => placeholderOption(Symbols.TrueOption, e)),
                    f.map(e => placeholderOption(Symbols.FalseOption, e)),
                    sep.map(e => placeholderOption(Symbols.SepOption, e)),
                    default.map(e => placeholderOption(Symbols.DefaultOption, e))
                ).flatten
            )
        )
      case ExprCompoundString(value, _, quoting) =>
        // Often/always an ExprCompoundString contains one or more empty
        // ValueStrings that we want to get rid of because they're useless
        // and can mess up formatting
        val filteredExprs = value.filter {
          case ValueString(s, _, Quoting.None) => s.nonEmpty
          case _                               => true
        }
        val strCtx = ctx.advanceTo(InStringState(quoting))
        CompoundString(filteredExprs.map(buildExpression(_, strCtx)), quoting = quoting)
      // other expressions need to be wrapped in a placeholder if they
      // appear in a string or command block
      case other =>
        val nextCtx =
          if (ctx.inString(resetInPlaceholder = true)) ctx.advanceTo(InPlaceholderState) else ctx
        val sized = other match {
          case ExprIdentifier(id, _) => Literal(id)
          case ExprAt(array, index, _) =>
            val arraySized = buildExpression(array, nextCtx)
            val prefix = Sequence(
                Vector(arraySized, Literal(Symbols.IndexOpen))
            )
            val suffix = Literal(Symbols.IndexClose)
            Container(
                Vector(buildExpression(index, nextCtx)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix),
                wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AsNeeded
            )
          case ExprIfThenElse(cond, tBranch, fBranch, _) =>
            val condSized = buildExpression(cond, nextCtx)
            val tSized = buildExpression(tBranch, nextCtx)
            val fSized = buildExpression(fBranch, nextCtx)
            Container(
                Vector(
                    Literal(Symbols.If),
                    condSized,
                    Literal(Symbols.Then),
                    tSized,
                    Literal(Symbols.Else),
                    fSized
                ),
                wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AsNeeded
            )
          case ExprApply(oper, _, Vector(ExprArray(args, _)), _)
              if Operator.Vectorizable.contains(oper) =>
            val operCtx = nextCtx.advanceTo(InOperationState(Some(oper)))
            val operands = args.map(buildExpression(_, operCtx))
            Operation(oper, operands, vectorizable = true, ctx)
          case ExprApply(oper, _, Vector(value), _) if Operator.All.contains(oper) =>
            val symbol = Operator.All(oper).symbol
            Sequence(
                Vector(Literal(symbol),
                       buildExpression(value, nextCtx.advanceTo(InOperationState())))
            )
          case ExprApply(oper, _, Vector(lhs, rhs), _) if Operator.All.contains(oper) =>
            val operCtx = nextCtx.advanceTo(InOperationState(Some(oper)))
            val operands = Vector(buildExpression(lhs, operCtx), buildExpression(rhs, operCtx))
            Operation(oper, operands, vectorizable = false, ctx)
          case ExprApply(funcName, _, elements, _) =>
            val prefix = Sequence(
                Vector(Literal(funcName), Literal(Symbols.FunctionCallOpen))
            )
            val suffix = Literal(Symbols.FunctionCallClose)
            Container(
                elements.map(buildExpression(_, nextCtx)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix),
                wrapping = if (ctx.inString()) Wrapping.Never else Wrapping.AsNeeded
            )
          case ExprGetName(e, id, _) =>
            val exprSized = buildExpression(e, nextCtx)
            val idLiteral = Literal(id)
            Sequence(
                Vector(exprSized, Literal(Symbols.Access), idLiteral)
            )
          case other => throw new Exception(s"Unrecognized expression $other")
        }
        if (ctx.inString(resetInPlaceholder = true)) {
          Placeholder(sized, ctx.placeholderOpen, ctx.inString())
        } else {
          sized
        }
    }
  }

  /**
    * Marker base class for Statements.
    */
  private trait Statement {

    /**
      * Format this statement. The `lineGenerator` must have `isLineBegun == false` on
      * both entry and exit.
      *
      * @param lineGenerator the lineGenerator
      */
    def format(lineGenerator: LineGenerator): Unit
  }

  private abstract class BaseStatement extends Statement {

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      formatContents(lineGenerator)
      lineGenerator.endLine()
    }

    /**
      * Format the contents of this statement. The `lineGenerator` must have
      * `isLineBegun == true` on both entry and exit.
      */
    protected def formatContents(lineGenerator: LineGenerator): Unit
  }

  private case class VersionStatement(version: Version) extends Statement {
    private val keywordToken = Literal(Symbols.Version)
    private val versionToken = Literal(targetVersion.getOrElse(version.value).name)

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      lineGenerator
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, versionToken))
      lineGenerator.endLine()
    }
  }

  private case class ImportStatement(importDoc: ImportDoc) extends BaseStatement {
    private val keywordToken = Literal(Symbols.Import)
    private val uriLiteral = {
      val (escaped, quoting) =
        try {
          (URI.create(importDoc.addr).toString, Quoting.Double)
        } catch {
          case _: Throwable => Utils.quoteString(importDoc.addr)
        }
      Literal(escaped, quoting)
    }
    private val nameTokens = Vector(Literal(Symbols.As), Literal(importDoc.namespace))
    private val aliasTokens = importDoc.aliases.map { alias =>
      Vector(Literal(Symbols.Alias), Literal(alias.id1), Literal(Symbols.As), Literal(alias.id2))
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, uriLiteral))
      lineGenerator.appendAll(nameTokens)
      aliasTokens.foreach { alias =>
        lineGenerator.derive(newWrapping = Wrapping.Always).appendAll(alias)
      }
    }
  }

  private case class Section(statements: Vector[Statement],
                             emtpyLineBetweenStatements: Boolean = false)
      extends Statement {
    override def format(lineGenerator: LineGenerator): Unit = {
      if (statements.nonEmpty) {
        statements.head.format(lineGenerator)
        statements.tail.foreach { section =>
          if (emtpyLineBetweenStatements) {
            lineGenerator.emptyLine()
          }
          section.format(lineGenerator)
        }
      }
    }
  }

  private def notCoercibleTo(expr: Expr, wdlType: T): Boolean = {
    val unify = Unification(TypeCheckingRegime.Moderate)
    !unify.isCoercibleTo(wdlType, expr.wdlType, UnificationContext(targetVersion))
  }

  private case class DeclarationStatement(name: String, wdlType: T, expr: Option[Expr] = None)
      extends BaseStatement {

    private val fixedExpr = expr.map(fixExpression(_))
    private val typeSized = {
      val newType = wdlType match {
        case T_String | T_Optional(T_String)
            if rewriteNonstandardUsages && fixedExpr.exists(notCoercibleTo(_, T_String)) =>
          // non-standard usage: String foo = 1 + 1
          // re-write to: Int foo = 1 + 1
          fixedExpr.get.wdlType
        case _ => wdlType
      }
      DataType.fromWdlType(newType)
    }
    private val nameLiteral = Literal(name)
    private val lhs = Vector(typeSized, nameLiteral)
    private val rhs = fixedExpr.map { e =>
      val eqToken = Literal(Symbols.Assignment)
      val exprAtom = buildExpression(e)
      Vector(eqToken, exprAtom)
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(lhs)
      if (rhs.isDefined) {
        lineGenerator.appendAll(rhs.get)
      }
    }
  }

  private abstract class BlockStatement(keyword: String, emitBracesIfEmpty: Boolean = true)
      extends Statement {
    def clause: Option[Sized] = None

    def body: Option[Statement] = None

    protected val keywordLiteral: Literal = Literal(keyword)
    private val clauseSized: Option[Sized] = clause
    private val bodyStatement: Option[Statement] = body
    private val (openLiteral, closeLiteral) = if (bodyStatement.isDefined || emitBracesIfEmpty) {
      (Some(Literal(Symbols.BlockOpen)), Some(Literal(Symbols.BlockClose)))
    } else {
      (None, None)
    }

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      lineGenerator.appendAll(Vector(Some(keywordLiteral), clauseSized, openLiteral).flatten)
      if (bodyStatement.isDefined) {
        lineGenerator.endLine()
        bodyStatement.get.format(lineGenerator.derive(increaseIndent = true))
        lineGenerator.beginLine()
      }
      if (closeLiteral.isDefined) {
        lineGenerator.append(closeLiteral.get)
      }
      lineGenerator.endLine()
    }
  }

  private case class InputsBlock(inputs: Vector[InputParameter])
      extends BlockStatement(Symbols.Input) {
    override def body: Option[Statement] =
      Some(Section(inputs.map {
        case RequiredInputParameter(name, wdlType) => DeclarationStatement(name, wdlType)
        case OverridableInputParameterWithDefault(name, wdlType, defaultExpr) =>
          DeclarationStatement(name, wdlType, Some(defaultExpr))
        case OptionalInputParameter(name, wdlType) => DeclarationStatement(name, wdlType)
      }))
  }

  private def buildMeta(metaValue: MetaValue): Sized = {
    metaValue match {
      // literal values
      case MetaValueNull() => Literal(Symbols.Null)
      case MetaValueString(value, quoting) =>
        val escaped = if (quoting != Quoting.None) {
          Utils.escape(value)
        } else {
          value
        }
        Literal(escaped, quoting = quoting)
      case MetaValueBoolean(value) => Literal(value)
      case MetaValueInt(value)     => Literal(value)
      case MetaValueFloat(value)   => Literal(value)
      case MetaValueArray(value) =>
        Container(
            value.map(buildMeta),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose)),
            wrapping = Wrapping.AllOrNone,
            continue = false
        )
      case MetaValueObject(value) =>
        Container(
            value.map {
              case (name, value) => KeyValue(Literal(name), buildMeta(value))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ObjectOpen), Literal(Symbols.ObjectClose)),
            Wrapping.Always,
            continue = false
        )
    }
  }

  private case class StructBlock(struct: StructDefinition) extends BlockStatement(Symbols.Struct) {
    override def clause: Option[Sized] = Some(Literal(struct.name))

    override def body: Option[Statement] =
      Some(Section(struct.members.map {
        case (name, wdlType) => DeclarationStatement(name, wdlType)
      }.toVector))
  }

  private case class OutputsBlock(outputs: Vector[OutputParameter])
      extends BlockStatement(Symbols.Output) {
    override def body: Option[Statement] =
      Some(Section(outputs.map { output =>
        DeclarationStatement(output.name, output.wdlType, Some(output.expr))
      }))
  }

  private case class MetaKVStatement(id: String, value: MetaValue) extends BaseStatement {
    private val idToken = Literal(id)
    private val delimToken = Literal(Symbols.KeyValueDelimiter)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildMeta(value)

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.derive(newWrapping = Wrapping.Never).appendAll(Vector(Sequence(lhs), rhs))
    }
  }

  private case class MetaBlock(keyword: String, kvs: Map[String, MetaValue])
      extends BlockStatement(keyword) {
    override def body: Option[Statement] =
      Some(Section(kvs.map {
        case (k, v) => MetaKVStatement(k, v)
      }.toVector))
  }

  private def splitWorkflowElements(elements: Vector[WorkflowElement]): Vector[Statement] = {
    var statements: Vector[Statement] = Vector.empty
    var privateVariables: Vector[PrivateVariable] = Vector.empty

    elements.foreach {
      case declaration: PrivateVariable => privateVariables :+= declaration
      case other =>
        if (privateVariables.nonEmpty) {
          statements :+= Section(privateVariables.map { decl =>
            DeclarationStatement(decl.name, decl.wdlType, Some(decl.expr))
          })
          privateVariables = Vector.empty
        }
        statements :+= (other match {
          case call: Call               => CallBlock(call)
          case scatter: Scatter         => ScatterBlock(scatter)
          case conditional: Conditional => ConditionalBlock(conditional)
          case other                    => throw new Exception(s"Unexpected workflow body element $other")
        })
    }

    if (privateVariables.nonEmpty) {
      statements :+= Section(privateVariables.map { decl =>
        DeclarationStatement(decl.name, decl.wdlType, Some(decl.expr))
      })
    }

    statements
  }

  private case class CallInputsStatement(inputs: Map[String, Expr]) extends BaseStatement {
    private val key = Literal(Symbols.Input)
    private val value = inputs.flatMap {
      case (_, ValueNone(_)) if omitNullCallInputs => None
      case (name, expr) =>
        val nameToken = Literal(name)
        val exprSized = buildExpression(fixExpression(expr))
        Some(
            Container(
                Vector(nameToken, Literal(Symbols.Assignment), exprSized)
            )
        )
    }.toVector

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      val kv = KeyValue(
          key,
          Container(value, delimiter = Some(Symbols.ArrayDelimiter), wrapping = Wrapping.Always)
      )
      kv.generateContents(lineGenerator)
    }
  }

  private case class CallBlock(call: Call)
      extends BlockStatement(Symbols.Call, emitBracesIfEmpty = false) {
    override def clause: Option[Sized] = Some(
        if (call.alias.isDefined) {
          val alias = call.alias.get
          // assuming all parts of the clause are adjacent
          val tokens =
            Vector(Literal(call.fullyQualifiedName), Literal(Symbols.As), Literal(alias))
          Container(tokens)
        } else {
          Literal(call.fullyQualifiedName)
        }
    )

    override def body: Option[Statement] =
      if (call.inputs.nonEmpty) {
        Some(CallInputsStatement(call.inputs))
      } else {
        None
      }
  }

  private case class ScatterBlock(scatter: Scatter) extends BlockStatement(Symbols.Scatter) {
    override def clause: Option[Sized] = {
      // assuming all parts of the clause are adjacent
      val openToken = Literal(Symbols.GroupOpen)
      val idToken = Literal(scatter.identifier)
      val inToken = Literal(Symbols.In)
      val exprAtom = buildExpression(fixExpression(scatter.expr))
      val closeToken = Literal(Symbols.GroupClose)
      Some(
          Container(
              Vector(idToken, inToken, exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(Section(splitWorkflowElements(scatter.body), emtpyLineBetweenStatements = true))
  }

  private case class ConditionalBlock(conditional: Conditional) extends BlockStatement(Symbols.If) {
    override def clause: Option[Sized] = {
      val exprAtom = buildExpression(fixExpression(conditional.expr))
      val openToken = Literal(Symbols.GroupOpen)
      val closeToken = Literal(Symbols.GroupClose)
      Some(
          Container(
              Vector(exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(Section(splitWorkflowElements(conditional.body), emtpyLineBetweenStatements = true))
  }

  private case class WorkflowBlock(workflow: Workflow) extends BlockStatement(Symbols.Workflow) {
    override def clause: Option[Sized] = Some(Literal(workflow.name))

    override def body: Option[Statement] = {
      val statements: Vector[Statement] = {
        val inputs = if (workflow.inputs.nonEmpty) {
          Some(InputsBlock(workflow.inputs))
        } else {
          None
        }
        val outputs = if (workflow.outputs.nonEmpty) {
          Some(OutputsBlock(workflow.outputs))
        } else {
          None
        }
        val bodySection = if (workflow.body.nonEmpty) {
          Some(Section(splitWorkflowElements(workflow.body), emtpyLineBetweenStatements = true))
        } else {
          None
        }
        Vector(
            inputs,
            bodySection,
            outputs,
            workflow.meta.map(meta => MetaBlock(Symbols.Meta, meta.kvs)),
            workflow.parameterMeta.map(paramMeta => MetaBlock(Symbols.ParameterMeta, paramMeta.kvs))
        ).flatten
      }
      Some(Section(statements, emtpyLineBetweenStatements = true))
    }
  }

  private case class CommandBlock(command: CommandSection) extends BaseStatement {
    // The command block is considered "preformatted" in that we don't try to reformat it.
    private val commandStartRegexp = "(?s)^([^\n\r]*)[\n\r]*(.*)$".r
    private val commandEndRegexp = "\n*\\s*$".r

    // check whether there is at least one non-whitespace command part
    private def hasCommand: Boolean = {
      command.parts.exists {
        case ValueString(value, _, _) => value.trim.nonEmpty
        case _                        => true
      }
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(
          Vector(Literal(Symbols.Command), Literal(Symbols.CommandOpen))
      )
      if (hasCommand) {
        // The parser swallows anyting after the opening token ('{' or '<<<') as part of the
        // command block, so we need to parse out any in-line WDL comment on the first line.
        val (headExpr: Expr, indent) = command.parts.head match {
          case v: ValueString =>
            v.value match {
              case commandStartRegexp(first, rest) =>
                first.trim match {
                  case s
                      if (
                          s.isEmpty || s.startsWith(Symbols.Comment)
                      ) && rest.trim.isEmpty && command.parts.size == 1 =>
                    // command block is empty
                    (v.copy(value = "")(v.loc), false)
                  case s if s.startsWith(Symbols.Comment) && rest.trim.isEmpty =>
                    // weird case, like there is a placeholder in the comment -
                    // we don't want to break anything so we'll just format the whole
                    // block as-is
                    (v, false)
                  case s if s.startsWith(Symbols.Comment) || s.isEmpty =>
                    // the first is empty or a WDL comment so we ignore it
                    (v.copy(value = rest)(v.loc), false)
                  case _ if rest.trim.isEmpty =>
                    // single-line expression
                    (v.copy(value = first.replaceFirst("^\\s+", ""))(v.loc), true)
                  case _ =>
                    // opening line has some real content, so leave as-is
                    (v, false)
                }
              case other =>
                throw new RuntimeException(s"unexpected command part ${other}")
            }
          case other => (other, false)
        }

        def trimLast(last: Expr): Expr = {
          last match {
            case v @ ValueString(s, _, _) =>
              // If the last part is just the whitespace before the close block, throw it out
              v.copy(commandEndRegexp.replaceFirstIn(s, ""))(v.loc)
            case other => other
          }
        }

        val newParts = if (command.parts.size == 1) {
          Vector(trimLast(headExpr))
        } else {
          val last = Vector(trimLast(command.parts.last))
          Vector(headExpr) ++ (
              if (command.parts.size == 2) {
                last
              } else {
                command.parts.slice(1, command.parts.size - 1) ++ last
              }
          )
        }

        val bodyGenerator = if (indent) {
          lineGenerator.derive(newIndentSteps = Some(1),
                               newSpacing = Spacing.Off,
                               newWrapping = Wrapping.Never)
        } else {
          lineGenerator.derive(newIndenting = Indenting.Never,
                               newSpacing = Spacing.Off,
                               newWrapping = Wrapping.Never)
        }

        bodyGenerator.endLine(continue = true)
        bodyGenerator.beginLine()
        newParts.foreach { expr =>
          val fixedExpr = fixExpression(expr, ExpressionContext.command)
          bodyGenerator.append(buildExpression(fixedExpr, ExpressionContext.command))
        }
        bodyGenerator.endLine()

        lineGenerator.beginLine()
      }

      lineGenerator.append(Literal(Symbols.CommandClose))
    }
  }

  private case class KVStatement(id: String, expr: Expr) extends BaseStatement {
    private val idToken = Literal(id)
    private val delimToken = Literal(Symbols.KeyValueDelimiter)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildExpression(fixExpression(expr))

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(Vector(Sequence(lhs), rhs))
    }
  }

  private case class RuntimeBlock(runtime: RuntimeSection) extends BlockStatement(Symbols.Runtime) {
    override def body: Option[Statement] =
      Some(Section(runtime.kvs.map {
        case (name, expr) => KVStatement(name, expr)
      }.toVector))
  }

  private case class TaskBlock(task: Task) extends BlockStatement(Symbols.Task) {
    override def clause: Option[Sized] =
      Some(Literal(task.name))

    override def body: Option[Statement] = {
      val statements: Vector[Statement] = {
        val inputs = task.inputs match {
          case v: Vector[InputParameter] if v.nonEmpty => Some(InputsBlock(v))
          case _                                       => None

        }
        val decls = task.privateVariables match {
          case v: Vector[PrivateVariable] if v.nonEmpty =>
            Some(Section(v.map { decl =>
              DeclarationStatement(decl.name, decl.wdlType, Some(decl.expr))
            }))
          case _ => None
        }
        val outputs = task.outputs match {
          case v: Vector[OutputParameter] if v.nonEmpty => Some(OutputsBlock(v))
          case _                                        => None
        }
        Vector(
            inputs,
            decls,
            Some(CommandBlock(task.command)),
            outputs,
            task.runtime.map(RuntimeBlock),
            task.hints.map(hints => MetaBlock(Symbols.Hints, hints.kvs)),
            task.meta.map(meta => MetaBlock(Symbols.Meta, meta.kvs)),
            task.parameterMeta.map(paramMeta => MetaBlock(Symbols.ParameterMeta, paramMeta.kvs))
        ).flatten
      }
      Some(Section(statements, emtpyLineBetweenStatements = true))
    }
  }

  private case class DocumentSections(document: Document) extends Statement {
    override def format(lineGenerator: LineGenerator): Unit = {
      // the version statement must be the first line in the file
      // so we start the section after appending it just in case
      // there were comments at the top of the source file
      val versionStatement = VersionStatement(document.version)
      versionStatement.format(lineGenerator)

      val imports = document.elements.collect { case imp: ImportDoc => imp }
      if (imports.nonEmpty) {
        lineGenerator.emptyLine()
        Section(imports.map(ImportStatement)).format(lineGenerator)
      }

      document.elements
        .collect {
          case struct: StructDefinition => StructBlock(struct)
        }
        .foreach { struct =>
          lineGenerator.emptyLine()
          struct.format(lineGenerator)
        }

      if (document.workflow.isDefined) {
        lineGenerator.emptyLine()
        WorkflowBlock(document.workflow.get).format(lineGenerator)
      }

      document.elements
        .collect {
          case task: Task => TaskBlock(task)
        }
        .foreach { task =>
          lineGenerator.emptyLine()
          task.format(lineGenerator)
        }
    }
  }

  def generateElement(element: Element,
                      headerLines: Vector[String] = Vector.empty): Vector[String] = {
    val stmt = element match {
      case d: Document =>
        val version = targetVersion.getOrElse(d.version.value)
        if (version < WdlVersion.V1) {
          throw new Exception(s"WDL version ${version} is not supported")
        }
        DocumentSections(d)
      case t: Task     => TaskBlock(t)
      case w: Workflow => WorkflowBlock(w)
      case other =>
        throw new RuntimeException(s"Formatting element of type ${other.getClass} not supported")
    }
    val lineGenerator = LineGenerator()
    stmt.format(lineGenerator)
    val headerComments = headerLines.map(s => s"# ${s}")
    val lines = lineGenerator.toVector
    headerComments ++ lines
  }

  def generateDocument(document: Document,
                       headerComment: Vector[String] = Vector.empty): Vector[String] = {
    generateElement(document, headerComment)
  }
}
