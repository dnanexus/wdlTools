package wdlTools.formatter

import java.net.URL

import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Comment, Parsers, TextSource, WdlVersion}
import wdlTools.util.Options

import scala.collection.mutable

case class WdlV1Formatter(opts: Options,
                          documents: mutable.Map[URL, Vector[String]] = mutable.Map.empty) {

  private case class FormatterDocument(document: Document) {
    private val commentLines: mutable.Map[Int, Position] = mutable.HashMap.empty

    private case class Literal(value: Any,
                               quoted: Boolean = false,
                               override val line: Int,
                               columns: (Option[Int], Option[Int]) = (None, None))
        extends Span
        with Atom {

      override lazy val column: Int = {
        columns match {
          case (Some(start), _) => start
          case (_, Some(end))   => end - length
          case _                => Position.TERMINAL
        }
      }

      /**
        * The last column in the span - position is 1-based and end-exclusive.
        */
      override def endColumn: Int = {
        columns match {
          case (_, Some(end))   => end
          case (Some(start), _) => start + length
          case _                => Position.TERMINAL
        }
      }

      override lazy val length: Int = toString.length

      override lazy val toString: String = {
        if (quoted) {
          s"${'"'}${value}${'"'}"
        } else {
          value.toString
        }
      }
    }

    private object Literal {
      def fromStart(value: Any, textSource: TextSource, quoted: Boolean = false): Literal = {
        Literal(value, quoted, textSource.line, (Some(textSource.col), None))
      }

      def fromStartPosition(value: Any,
                            line: Int,
                            column: Int = 1,
                            quoted: Boolean = false): Literal = {
        Literal(value, quoted, line, (Some(column), None))
      }

      def fromEnd(value: Any, textSource: TextSource, quoted: Boolean = false): Literal = {
        Literal(value, quoted, textSource.line, (None, Some(textSource.endCol)))
      }

      def fromEndPosition(value: Any,
                          line: Int,
                          column: Int = Position.TERMINAL,
                          quoted: Boolean = false): Literal = {
        Literal(value, quoted, line, (None, Some(column)))
      }

      def fromPrev(value: Any, prev: Span, quoted: Boolean = false): Literal = {
        Literal(value, quoted, prev.line, (Some(prev.endColumn), None))
      }

      def fromNext(value: Any, next: Span, quoted: Boolean = false): Literal = {
        Literal(value, quoted, next.line, (None, Some(next.column)))
      }

      def between(value: String,
                  prev: Span,
                  next: Span,
                  quoted: Boolean = false,
                  preferPrev: Boolean = false): Literal = {
        if (prev.line == next.line) {
          require(prev.endColumn < next.column)
          Literal.fromPrev(value, prev, quoted)
        } else if (preferPrev) {
          Literal.fromPrev(value, prev, quoted)
        } else {
          Literal.fromNext(value, next, quoted)
        }
      }

      def chainFromStart(values: Vector[Any], start: TextSource): Vector[Literal] = {
        var prev = Literal.fromStart(values.head, start)
        Vector(prev) ++ values.tail.map { v =>
          val next = Literal.fromPrev(v, prev)
          prev = next
          next
        }
      }

      def chainFromPrev(values: Vector[Any], prev: Span): Vector[Literal] = {
        var p: Span = prev
        values.map { v =>
          val next = Literal.fromPrev(v, prev)
          p = next
          next
        }
      }
    }

    private trait Bound {
      def bounds: TextSource
    }

    private trait BoundSpan extends Span with Bound {
      override def line: Int = bounds.line

      override def column: Int = bounds.col

      override def endColumn: Int = bounds.endCol
    }

    private abstract class AdjacentBase(spans: Vector[Span],
                                        wrapping: Wrapping = Wrapping.Never,
                                        quoted: Boolean = false)
        extends Span
        with Composite {
      override lazy val length: Int = spans.map(_.length).sum + (if (quoted) 2 else 0)

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        println(s"spans ${spans}")
        val adjacentFormatter = lineFormatter.derive(unspaced = true, newWrapping = wrapping)
        if (quoted) {
          adjacentFormatter.append(Literal.fromStartPosition(Symbols.QuoteOpen, line, column))
        }
        adjacentFormatter.appendAll(spans)
        if (quoted) {
          adjacentFormatter.append(Literal.fromEndPosition(Symbols.QuoteClose, line, endColumn))
        }
      }
    }

    private case class Adjacent(spans: Vector[Span],
                                wrapping: Wrapping = Wrapping.Never,
                                quoted: Boolean = false)
        extends AdjacentBase(spans, wrapping, quoted) {
      require(spans.nonEmpty)
      require(spans.tail.forall(_.line == spans.head.line))

      override def line: Int = spans.head.line

      override def column: Int = spans.head.column

      override def endColumn: Int = spans.last.endColumn
    }

    private case class BoundAdjacent(spans: Vector[Span],
                                     override val bounds: TextSource,
                                     wrapping: Wrapping = Wrapping.Never,
                                     quoted: Boolean = false)
        extends AdjacentBase(spans, wrapping, quoted)
        with BoundSpan

    private trait Multiline extends Ordered[Multiline] {
      def line: Int

      def endLine: Int

      lazy val lineRange: Range = line to endLine

      override def compare(that: Multiline): Int = {
        line - that.line match {
          case 0     => endLine - that.endLine
          case other => other
        }
      }
    }

    private abstract class Group(prefix: Option[Span] = None,
                                 suffix: Option[Span] = None,
                                 wrapping: Wrapping = Wrapping.Never)
        extends Span
        with Composite {
      protected lazy val prefixLength: Int = prefix.map(_.length).getOrElse(0)
      protected lazy val suffixLength: Int = suffix.map(_.length).getOrElse(0)

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        val wrapAndIndentPrefix = wrapping != Wrapping.Never && prefixLength > lineFormatter.lengthRemaining

        if (prefix.isDefined) {
          if (wrapAndIndentPrefix) {
            lineFormatter.endLine(continue = true)
            lineFormatter.beginLine()
          }
          lineFormatter.append(prefix.get)
        }

        val wrapBeforeBody = wrapping == Wrapping.Always || length > lineFormatter.lengthRemaining

        val bodyFormatter = lineFormatter.derive(indent = wrapAndIndentPrefix && wrapBeforeBody,
                                                 newWrapping = wrapping)

        if (wrapBeforeBody) {
          bodyFormatter.endLine(continue = true)
          bodyFormatter.beginLine()
        }

        formatBody(bodyFormatter)

        println(s"suffix ${suffix}")

        if (suffix.isDefined) {
          if (wrapBeforeBody) {
            lineFormatter.endLine(continue = wrapAndIndentPrefix)
            lineFormatter.beginLine()
          }
          lineFormatter.append(suffix.get)
        }
      }

      def formatBody(lineFormatter: LineFormatter): Unit
    }

    private abstract class Container(items: Vector[Span],
                                     separator: String,
                                     prefix: Option[Span] = None,
                                     suffix: Option[Span] = None,
                                     wrapping: Wrapping = Wrapping.AsNeeded)
        extends Group(prefix = prefix, suffix = suffix, wrapping = wrapping)
        with Multiline {

      private lazy val itemStr: String = items.mkString(separator)
      private val itemLength: Int = itemStr.length
      private val itemSpans: Vector[Span] = items.zipWithIndex.map {
        case (item, i) if i < items.size - 1 =>
          val delimiterLiteral = Literal.fromPrev(separator, item)
          Adjacent(Vector(item, delimiterLiteral))
        case (item, _) => item
      }

      override def length: Int = {
        itemLength + prefixLength + suffixLength
      }

      def formatBody(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(itemSpans)
      }
    }

    private trait BoundMultiline extends Bound with Multiline {
      override def line: Int = bounds.line

      override def endLine: Int = bounds.endLine
    }

    private trait BoundMultilineSpan extends BoundSpan with BoundMultiline {
      override def line: Int = bounds.line
    }

    private case class BoundDelimitedContainer(items: Vector[Span],
                                               prefix: Option[Span] = None,
                                               suffix: Option[Span] = None,
                                               wrapping: Wrapping = Wrapping.AsNeeded,
                                               override val bounds: TextSource)
        extends Container(items,
                          separator = s"${Symbols.ArrayDelimiter} ",
                          prefix,
                          suffix,
                          wrapping)
        with BoundMultilineSpan

    private case class BoundSpacedContainer(items: Vector[Span],
                                            prefix: Option[Span] = None,
                                            suffix: Option[Span] = None,
                                            wrapping: Wrapping = Wrapping.Never,
                                            override val bounds: TextSource)
        extends Container(items, separator = " ", prefix, suffix, wrapping)
        with BoundMultilineSpan

    private case class KeyValue(key: Span,
                                value: Span,
                                delimiter: String = Symbols.KeyValueDelimiter,
                                override val bounds: TextSource)
        extends BoundMultilineSpan
        with Composite {
      private val delimiterLiteral: Literal = Literal.fromPrev(delimiter, key)

      override def length: Int = key.length + delimiterLiteral.length + value.length + 1

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Adjacent(Vector(key, delimiterLiteral)), value))
      }
    }

    private object DataType {
      def buildDataType(name: String,
                        inner1: Option[Span] = None,
                        inner2: Option[Span] = None,
                        quantifier: Option[String] = None,
                        textSource: TextSource): Span = {
        val nameLiteral: Literal = Literal.fromStart(name, textSource)
        val quantifierLiteral: Option[Literal] =
          quantifier.map(sym => Literal.fromEnd(sym, textSource))
        if (inner1.isDefined) {
          // making the assumption that the open token comes directly after the name
          val openLiteral = Literal.fromPrev(Symbols.TypeParamOpen, nameLiteral)
          // making the assumption that the close token comes directly before the quantifier (if any)
          val closeLiteral = if (quantifierLiteral.isDefined) {
            Literal.fromNext(Symbols.TypeParamClose, quantifierLiteral.get)
          } else {
            Literal.fromEnd(Symbols.TypeParamClose, textSource)
          }
          BoundDelimitedContainer(
              Vector(inner1, inner2).flatten,
              prefix = Some(Adjacent(Vector(nameLiteral, openLiteral))),
              suffix = Some(Adjacent(Vector(Some(closeLiteral), quantifierLiteral).flatten)),
              bounds = textSource
          )
        } else if (quantifier.isDefined) {
          Adjacent(Vector(nameLiteral, quantifierLiteral.get))
        } else {
          nameLiteral
        }
      }

      private def isPrimitiveType(wdlType: Type): Boolean = {
        wdlType match {
          case _: TypeString  => true
          case _: TypeBoolean => true
          case _: TypeInt     => true
          case _: TypeFloat   => true
          case _: TypeFile    => true
          case _              => false
        }
      }

      def fromWdlType(wdlType: Type, quantifier: Option[Literal] = None): Span = {
        wdlType match {
          case TypeOptional(inner, text) =>
            fromWdlType(inner, quantifier = Some(Literal.fromEnd(Symbols.Optional, text)))
          case TypeArray(inner, nonEmpty, text) =>
            val quant = if (nonEmpty) {
              Some(Symbols.NonEmpty)
            } else {
              None
            }
            buildDataType(Symbols.ArrayType,
                          Some(fromWdlType(inner)),
                          quantifier = quant,
                          textSource = text)
          case TypeMap(keyType, valueType, text) if isPrimitiveType(keyType) =>
            buildDataType(Symbols.MapType,
                          Some(fromWdlType(keyType)),
                          Some(fromWdlType(valueType)),
                          textSource = text)
          case TypePair(left, right, text) =>
            buildDataType(Symbols.PairType,
                          Some(fromWdlType(left)),
                          Some(fromWdlType(right)),
                          textSource = text)
          case TypeStruct(name, _, text) => Literal.fromStart(name, text)
          case TypeObject(text)          => Literal.fromStart(Symbols.ObjectType, text)
          case TypeString(text)          => Literal.fromStart(Symbols.StringType, text)
          case TypeBoolean(text)         => Literal.fromStart(Symbols.BooleanType, text)
          case TypeInt(text)             => Literal.fromStart(Symbols.IntType, text)
          case TypeFloat(text)           => Literal.fromStart(Symbols.FloatType, text)
          case other                     => throw new Exception(s"Unrecognized type $other")
        }
      }
    }

    private abstract class ExpressionGroup(prefix: Option[Span] = None,
                                           suffix: Option[Span] = None,
                                           inString: Boolean,
                                           override val bounds: TextSource)
        extends Group(prefix = prefix,
                      suffix = suffix,
                      wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded)
        with BoundMultilineSpan

    private case class Operation(oper: String,
                                 lhs: Span,
                                 rhs: Span,
                                 grouped: Boolean = false,
                                 inString: Boolean,
                                 override val bounds: TextSource)
        extends ExpressionGroup(prefix = if (grouped) {
          Some(Literal.fromStart(Symbols.GroupOpen, bounds))
        } else {
          None
        }, suffix = if (grouped) {
          Some(Literal.fromEnd(Symbols.GroupClose, bounds))
        } else {
          None
        }, inString, bounds) {

      private val operLiteral = Literal.between(oper, lhs, rhs)

      override def length: Int = {
        val parenLength = if (grouped) {
          2
        } else {
          0
        }
        lhs.length + oper.length + rhs.length + 2 + parenLength
      }

      override def formatBody(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(lhs, operLiteral, rhs))
      }
    }

    private case class Placeholder(value: Span,
                                   open: String = Symbols.PlaceholderOpenDollar,
                                   close: String = Symbols.PlaceholderClose,
                                   options: Option[Vector[Span]] = None,
                                   inString: Boolean,
                                   override val bounds: TextSource)
        extends ExpressionGroup(prefix = Some(Literal.fromStart(open, bounds)),
                                suffix = Some(Literal.fromEnd(close, bounds)),
                                inString = inString,
                                bounds = bounds) {

      override def length: Int = {
        value.length + open.length + close.length + options
          .map(_.map(_.length + 1).sum)
          .getOrElse(0)
      }

      override def formatBody(lineFormatter: LineFormatter): Unit = {
        if (options.isDefined) {
          lineFormatter.appendAll(options.get)
        }
        lineFormatter.append(value)
      }
    }

    private def buildExpression(
        expr: Expr,
        placeholderOpen: String = Symbols.PlaceholderOpenDollar,
        inStringOrCommand: Boolean = false,
        inPlaceholder: Boolean = false,
        inOperation: Boolean = false
    ): Span = {

      /**
        * Creates a Literal whose `quoting` depends on whether we're already inside a string literal.
        *
        * @param value the value to wrap
        * @return a Literal
        */
      def literal(value: String, textSource: TextSource): Literal = {
        Literal.fromStart(value, textSource, quoted = inPlaceholder || !inStringOrCommand)
      }

      /**
        * Builds an expression that occurs nested within another expression. By default, passes all the current
        * parameter values to the nested call.
        *
        * @param nestedExpression the nested Expr
        * @param placeholderOpen  override the current value of `placeholderOpen`
        * @param inString         override the current value of `inString`
        * @param inPlaceholder    override the current value of `inPlaceholder`
        * @param inOperation      override the current value of `inOperation`
        * @return a Span
        */
      def nested(nestedExpression: Expr,
                 placeholderOpen: String = placeholderOpen,
                 inString: Boolean = inStringOrCommand,
                 inPlaceholder: Boolean = inPlaceholder,
                 inOperation: Boolean = inOperation): Span = {
        buildExpression(nestedExpression,
                        placeholderOpen = placeholderOpen,
                        inStringOrCommand = inString,
                        inPlaceholder = inPlaceholder,
                        inOperation = inOperation)
      }

      def unirary(oper: String, value: Expr, textSource: TextSource): Span = {
        val operSpan = Literal.fromStart(oper, textSource)
        Adjacent(Vector(operSpan, nested(value, inOperation = true)))
      }

      def operation(oper: String, lhs: Expr, rhs: Expr, textSource: TextSource): Span = {
        Operation(
            oper,
            nested(lhs, inPlaceholder = inStringOrCommand, inOperation = true),
            nested(rhs, inPlaceholder = inStringOrCommand, inOperation = true),
            grouped = inOperation,
            inString = inStringOrCommand,
            textSource
        )
      }

      def option(name: String, value: Expr): Span = {
        val exprSpan = nested(value, inPlaceholder = true)
        val eqLiteral = Literal.fromNext(Symbols.Assignment, exprSpan)
        val nameLiteral = Literal.fromNext(name, eqLiteral)
        Adjacent(Vector(nameLiteral, eqLiteral, exprSpan))
      }

      expr match {
        // literal values
        case ValueNull(text)           => Literal.fromStart(Symbols.Null, text)
        case ValueString(value, text)  => literal(value, text)
        case ValueFile(value, text)    => literal(value, text)
        case ValueBoolean(value, text) => Literal.fromStart(value, text)
        case ValueInt(value, text)     => Literal.fromStart(value, text)
        case ValueFloat(value, text)   => Literal.fromStart(value, text)
        case ExprPair(left, right, text) if !(inStringOrCommand || inPlaceholder) =>
          BoundDelimitedContainer(
              Vector(nested(left), nested(right)),
              prefix = Some(Literal.fromStart(Symbols.GroupOpen, text)),
              suffix = Some(Literal.fromEnd(Symbols.GroupClose, text)),
              bounds = text
          )
        case ExprArray(value, text) =>
          BoundDelimitedContainer(
              value.map(nested(_)),
              prefix = Some(Literal.fromStart(Symbols.ArrayLiteralOpen, text)),
              suffix = Some(Literal.fromEnd(Symbols.ArrayLiteralClose, text)),
              bounds = text
          )
        case ExprMap(value, text) =>
          BoundDelimitedContainer(
              value.map {
                case ExprMapItem(k, v, itemText) =>
                  KeyValue(nested(k), nested(v), bounds = itemText)
              },
              prefix = Some(Literal.fromStart(Symbols.MapOpen, text)),
              suffix = Some(Literal.fromEnd(Symbols.MapClose, text)),
              wrapping = Wrapping.Always,
              bounds = text
          )
        case ExprObject(value, text) =>
          BoundDelimitedContainer(
              value.map {
                case ExprObjectMember(k, v, memberText) =>
                  KeyValue(Literal.fromStart(k, memberText), nested(v), bounds = memberText)
              },
              prefix = Some(Literal.fromStart(Symbols.ObjectOpen, text)),
              suffix = Some(Literal.fromEnd(Symbols.ObjectClose, text)),
              wrapping = Wrapping.Always,
              bounds = text
          )
        // placeholders
        case ExprPlaceholderEqual(t, f, value, text) =>
          Placeholder(
              nested(value, inPlaceholder = true),
              placeholderOpen,
              options = Some(
                  Vector(
                      option(Symbols.TrueOption, t),
                      option(Symbols.FalseOption, f)
                  )
              ),
              inString = inStringOrCommand,
              bounds = text
          )
        case ExprPlaceholderDefault(default, value, text) =>
          Placeholder(nested(value, inPlaceholder = true),
                      placeholderOpen,
                      options = Some(Vector(option(Symbols.DefaultOption, default))),
                      inString = inStringOrCommand,
                      bounds = text)
        case ExprPlaceholderSep(sep, value, text) =>
          Placeholder(nested(value, inPlaceholder = true),
                      placeholderOpen,
                      options = Some(Vector(option(Symbols.SepOption, sep))),
                      inString = inStringOrCommand,
                      bounds = text)
        case ExprCompoundString(value, text) if !inPlaceholder =>
          BoundAdjacent(value.map(nested(_, inString = true)), text, quoted = !inStringOrCommand)
        // other expressions need to be wrapped in a placeholder if they
        // appear in a string or command block
        case other =>
          val span = other match {
            case ExprUniraryPlus(value, text)  => unirary(Symbols.UnaryPlus, value, text)
            case ExprUniraryMinus(value, text) => unirary(Symbols.UnaryMinus, value, text)
            case ExprNegate(value, text)       => unirary(Symbols.LogicalNot, value, text)
            case ExprLor(a, b, text)           => operation(Symbols.LogicalOr, a, b, text)
            case ExprLand(a, b, text)          => operation(Symbols.LogicalAnd, a, b, text)
            case ExprEqeq(a, b, text)          => operation(Symbols.Equality, a, b, text)
            case ExprLt(a, b, text)            => operation(Symbols.LessThan, a, b, text)
            case ExprLte(a, b, text)           => operation(Symbols.LessThanOrEqual, a, b, text)
            case ExprGt(a, b, text)            => operation(Symbols.GreaterThan, a, b, text)
            case ExprGte(a, b, text)           => operation(Symbols.GreaterThanOrEqual, a, b, text)
            case ExprNeq(a, b, text)           => operation(Symbols.Inequality, a, b, text)
            case ExprAdd(a, b, text)           => operation(Symbols.Addition, a, b, text)
            case ExprSub(a, b, text)           => operation(Symbols.Subtraction, a, b, text)
            case ExprMul(a, b, text)           => operation(Symbols.Multiplication, a, b, text)
            case ExprDivide(a, b, text)        => operation(Symbols.Division, a, b, text)
            case ExprMod(a, b, text)           => operation(Symbols.Remainder, a, b, text)
            case ExprIdentifier(id, text)      => Literal.fromStart(id, text)
            case ExprAt(array, index, text) =>
              val arraySpan = nested(array, inPlaceholder = inStringOrCommand)
              BoundDelimitedContainer(
                  Vector(nested(index, inPlaceholder = inStringOrCommand)),
                  prefix = Some(
                      Adjacent(
                          Vector(arraySpan, Literal.fromPrev(Symbols.IndexOpen, arraySpan))
                      )
                  ),
                  suffix = Some(Literal.fromEnd(Symbols.IndexClose, text)),
                  bounds = text
              )
            case ExprIfThenElse(cond, tBranch, fBranch, text) =>
              val condSpan = nested(cond, inOperation = true, inPlaceholder = inStringOrCommand)
              val tSpan = nested(tBranch, inOperation = true, inPlaceholder = inStringOrCommand)
              val fSpan = nested(fBranch, inOperation = true, inPlaceholder = inStringOrCommand)
              BoundSpacedContainer(
                  Vector(
                      Literal.fromStart(Symbols.If, text),
                      condSpan,
                      Literal.between(Symbols.Then, condSpan, tSpan),
                      tSpan,
                      Literal.between(Symbols.Else, tSpan, fSpan),
                      fSpan
                  ),
                  wrapping = Wrapping.AsNeeded,
                  bounds = text
              )
            case ExprApply(funcName, elements, text) =>
              BoundDelimitedContainer(
                  elements.map(nested(_, inPlaceholder = inStringOrCommand)),
                  prefix = Some(
                      Adjacent(
                          Literal.chainFromStart(Vector(funcName, Symbols.FunctionCallOpen), text)
                      )
                  ),
                  suffix = Some(Literal.fromEnd(Symbols.FunctionCallClose, text)),
                  bounds = text
              )
            case ExprGetName(e, id, text) =>
              val exprSpan = nested(e, inPlaceholder = inStringOrCommand)
              val idLiteral = Literal.fromEnd(id, text)
              Adjacent(
                  Vector(exprSpan, Literal.between(Symbols.Access, exprSpan, idLiteral), idLiteral)
              )
            case other => throw new Exception(s"Unrecognized expression $other")
          }
          if (inStringOrCommand && !inPlaceholder) {
            Placeholder(span, placeholderOpen, inString = inStringOrCommand, bounds = other.text)
          } else {
            span
          }
      }
    }

    /**
      * Marker base class for Statements.
      */
    private trait Statement extends Multiline {

      /**
        * Format this statement. The `lineFormatter` must have `isLineBegun == false` on
        * both entry and exit.
        *
        * @param lineFormatter the lineFormatter
        */
      def format(lineFormatter: LineFormatter): Unit
    }

    private abstract class BoundedStatement(override val bounds: TextSource)
        extends Statement
        with BoundMultiline {

      override def format(lineFormatter: LineFormatter): Unit = {
        lineFormatter.beginLine()
        formatContents(lineFormatter)
        lineFormatter.endLine()
      }

      /**
        * Format the contents of this statement. The `lineFormatter` must have
        * `isLineBegun == true` on both entry and exit.
        */
      protected def formatContents(lineFormatter: LineFormatter): Unit
    }

    private case class VersionStatement(version: Version) extends BoundedStatement(version.text) {
      private val keywordToken = Literal.fromStart(Symbols.Version, version.text)
      private val versionToken = Literal.fromEnd(WdlVersion.V1.name, version.text)

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter
          .derive(newWrapping = Wrapping.Never)
          .appendAll(Vector(keywordToken, versionToken))
      }
    }

    private case class ImportStatement(importDoc: ImportDoc)
        extends BoundedStatement(importDoc.text) {
      private val keywordToken = Literal.fromStart(Symbols.Import, importDoc.text)
      // assuming URL comes directly after keyword
      private val urlLiteral = Literal.fromPrev(importDoc.addr.value, keywordToken)
      // assuming namespace comes directly after url
      private val nameTokens = importDoc.name.map { name =>
        Literal.chainFromPrev(Vector(Symbols.As, name.value), urlLiteral)
      }
      private val aliasTokens = importDoc.aliases.map { alias =>
        Literal.chainFromStart(Vector(Symbols.Alias, alias.id1, Symbols.As, alias.id2), alias.text)
      }

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter
          .derive(newWrapping = Wrapping.Never)
          .appendAll(Vector(keywordToken, urlLiteral))
        if (nameTokens.isDefined) {
          lineFormatter.appendAll(nameTokens.get)
        }
        aliasTokens.foreach { alias =>
          lineFormatter.derive(newWrapping = Wrapping.Always).appendAll(alias)
        }
      }
    }

    private abstract class Section(emtpyLineBetweenStatements: Boolean = false,
                                   topCommentsAllowed: Boolean = true)
        extends Statement {
      def statements: Vector[Statement]

      protected lazy val sortedStatements: Vector[Statement] = statements.sortWith(_ < _)

      override def format(lineFormatter: LineFormatter): Unit = {
        val comments = document.comments.filterWithin(lineRange)

        if (comments.nonEmpty) {
          if (statements.nonEmpty) {
            val ranges = sortedStatements.map(_.lineRange)
            val top: Range = comments.minLine until sortedStatements.head.line
            val bottom: Range = (sortedStatements.last.endLine + 1) until comments.maxLine
            // Mapping of section to (before?, emptyLineBetween?, comments).
            // A line comment sticks with the closest statement (before or after) and defaults
            // to the next statement if it is equidistant to both. To do this, we first need to
            // sort the sections by their original order.
            val sectionToComment: Map[Range, (Boolean, Boolean, Vector[Comment])] =
              (Vector(top) ++ ranges ++ Vector(bottom))
                .filter(_.nonEmpty)
                .sliding(2)
                .flatMap {
                  case Vector(l, r) =>
                    val betweenComments = comments.filterWithin((l.last + 1).until(r.head))
                    //println(s"${l} ${r} ${betweenComments}")
                    if (betweenComments.nonEmpty) {
                      val sortedComments = betweenComments.toSortedVector
                      val beforeDist = sortedComments.head.text.line - l.last
                      val afterDist = r.head - sortedComments.last.text.endLine
                      if (!topCommentsAllowed && top.nonEmpty && l == top) {
                        Some(r -> (true, true, sortedComments))
                      } else if (afterDist <= beforeDist) {
                        Some(r -> (true, afterDist > 1, sortedComments))
                      } else {
                        Some(l -> (false, beforeDist > 1, sortedComments))
                      }
                    } else {
                      None
                    }
                  case _ => None
                }
                .toMap

            def addSection(section: Statement): Unit = {
              if (sectionToComment.contains(section.lineRange)) {
                val (isBefore, emptyLine, sectionComments) = sectionToComment(section.lineRange)
                if (isBefore) {
                  lineFormatter.addLineComments(sectionComments)
                  if (emptyLine) {
                    lineFormatter.emptyLine()
                  }
                  section.format(lineFormatter)
                } else {
                  section.format(lineFormatter)
                  if (emptyLine) {
                    lineFormatter.emptyLine()
                  }
                  lineFormatter.addLineComments(sectionComments)
                }
              } else {
                section.format(lineFormatter)
              }
            }

            if (top.nonEmpty && sectionToComment.contains(top)) {
              lineFormatter.addLineComments(sectionToComment(top)._3)
              if (emtpyLineBetweenStatements) {
                lineFormatter.emptyLine()
              }
            }

            addSection(statements.head)
            statements.tail.foreach { section =>
              if (emtpyLineBetweenStatements) {
                lineFormatter.emptyLine()
              }
              addSection(section)
            }

            if (bottom.nonEmpty && sectionToComment.contains(bottom)) {
              if (emtpyLineBetweenStatements) {
                lineFormatter.emptyLine()
              }
              lineFormatter.addLineComments(sectionToComment(bottom)._3)
            }
          } else {
            lineFormatter.addLineComments(comments.toSortedVector)
          }
        } else if (statements.nonEmpty) {
          statements.head.format(lineFormatter)
          statements.tail.foreach { section =>
            if (emtpyLineBetweenStatements) {
              lineFormatter.emptyLine()
            }
            section.format(lineFormatter)
          }
        }
      }
    }

    private abstract class OpenSection(emtpyLineBetweenStatements: Boolean = false,
                                       topCommentsAllowed: Boolean = true)
        extends Section(emtpyLineBetweenStatements, topCommentsAllowed) {
      // require(statements.nonEmpty)

      override def line: Int = sortedStatements.head.line

      override def endLine: Int = sortedStatements.last.endLine
    }

    private abstract class InnerSection(val bounds: TextSource,
                                        emtpyLineBetweenStatements: Boolean = false,
                                        topCommentsAllowed: Boolean = true)
        extends Section(emtpyLineBetweenStatements, topCommentsAllowed) {
      override def line: Int = bounds.line + 1

      override def endLine: Int = bounds.endLine - 1
    }

    private case class ImportsSection(imports: Vector[ImportDoc]) extends OpenSection {
      override val statements: Vector[Statement] = {
        imports.map(ImportStatement)
      }
    }

    private abstract class DeclarationBase(name: String,
                                           wdlType: Type,
                                           expr: Option[Expr] = None,
                                           override val bounds: TextSource)
        extends BoundedStatement(bounds) {

      private val typeSpan = DataType.fromWdlType(wdlType)
      // assuming name follows direclty after type
      private val nameLiteral = Literal.fromPrev(name, typeSpan)
      private val lhs = Vector(typeSpan, nameLiteral)
      private val rhs = expr.map { e =>
        val eqToken = Literal.fromPrev(Symbols.Assignment, nameLiteral)
        val exprAtom = buildExpression(e)
        Vector(eqToken, exprAtom)
      }

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(lhs)
        if (rhs.isDefined) {
          println(s"rhs ${rhs.get}")
          lineFormatter.appendAll(rhs.get)
        }
      }
    }

    private case class DeclarationStatement(decl: Declaration)
        extends DeclarationBase(decl.name, decl.wdlType, decl.expr, decl.text)

    private case class DeclarationsSection(declarations: Vector[Declaration]) extends OpenSection {
      override val statements: Vector[Statement] = {
        declarations.map(DeclarationStatement)
      }
    }

    private abstract class BlockStatement(keyword: String, override val bounds: TextSource)
        extends BoundedStatement(bounds) {

      def clause: Option[Span] = None

      def body: Option[Statement] = None

      protected val keywordLiteral: Literal = Literal.fromStart(keyword, bounds)

      private val clauseSpan: Option[Span] = clause
      // assume the open brace is on the same line as the keyword/clause
      private val openLiteral =
        Literal.fromPrev(Symbols.BlockOpen, clauseSpan.getOrElse(keywordLiteral))
      private val bodyStatement: Option[Statement] = body
      private val closeLiteral = Literal.fromEnd(Symbols.BlockClose, bounds)

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Some(keywordLiteral), clauseSpan, Some(openLiteral)).flatten)
        if (bodyStatement.isDefined) {
          lineFormatter.endLine()
          bodyStatement.get.format(lineFormatter.derive(indent = true))
          lineFormatter.beginLine()
        }
        lineFormatter.append(closeLiteral)
      }
    }

    private case class InputsBlock(inputs: Vector[Declaration], override val bounds: TextSource)
        extends BlockStatement(Symbols.Input, bounds) {
      override def body: Option[Statement] = Some(DeclarationsSection(inputs))
    }

    /**
      * Due to the lack of a formal input section in draft-2, inputs and other declarations (i.e. those
      * that require evaluation and thus are not allowed as inputs) may be mixed in the source text. A
      * TopDeclarations section takes both inputs and other declarations that appear at the top of a
      * workflow or task and formats them correctly using one Multiline for each sub-group of declarations
      * that covers all of the lines starting from the previous group (or startLine for the first element)
      * until the last line of the last declaration in the group.
      */
    private case class TopDeclarations(inputs: Vector[Statement],
                                       other: Vector[Statement],
                                       override val line: Int)
        extends Statement {

      override val endLine: Int = math.max(inputs.last.endLine, other.last.endLine)

      override def format(lineFormatter: LineFormatter): Unit = {
        val keywordLiteral: Literal = Literal.fromStartPosition(Symbols.Input, line = line)
        val openLiteral = Literal.fromPrev(Symbols.BlockOpen, keywordLiteral)
        val closeLiteral = Literal.fromEndPosition(Symbols.BlockClose, endLine)

        lineFormatter.beginLine()
        lineFormatter.appendAll(Vector(keywordLiteral, openLiteral))
        lineFormatter.endLine()

        case class TopDeclarationsSection(override val statements: Vector[Statement],
                                          override val line: Int,
                                          override val endLine: Int)
            extends Section

        val inputFormatter = lineFormatter.derive(indent = true)
        var groupStart = line
        var inputItr = inputs.iterator.buffered
        var otherItr = other.iterator.buffered
        val otherGroups: mutable.Buffer[TopDeclarationsSection] = mutable.ArrayBuffer.empty

        def nextGroup(
            a: BufferedIterator[Statement],
            b: BufferedIterator[Statement]
        ): (Option[TopDeclarationsSection], BufferedIterator[Statement]) = {
          val (groupItr, aNew) = if (b.hasNext) {
            a.span(_.line < b.head.line)
          } else {
            (a, Iterator.empty)
          }
          val group = if (groupItr.nonEmpty) {
            val groupStatements = groupItr.toVector
            val end = groupStatements.last.endLine
            val section = TopDeclarationsSection(groupStatements, groupStart, end)
            groupStart = end
            Some(section)
          } else {
            None
          }
          (group, aNew.buffered)
        }

        while (inputItr.hasNext) {
          val otherResult = nextGroup(otherItr, inputItr)
          if (otherResult._1.isDefined) {
            //println(otherResult._1.get)
            otherGroups.append(otherResult._1.get)
          }
          otherItr = otherResult._2

          val inputResult = nextGroup(inputItr, otherItr)
          if (inputResult._1.isDefined) {
            //println(inputResult._1.get)
            inputResult._1.get.format(inputFormatter)
          }
          inputItr = inputResult._2
        }

        lineFormatter.beginLine()
        lineFormatter.append(closeLiteral)
        lineFormatter.endLine()

        lineFormatter.emptyLine()

        if (otherGroups.nonEmpty) {
          otherGroups.toVector.foreach(group => group.format(lineFormatter))
        }
        if (otherItr.hasNext) {
          nextGroup(otherItr, inputItr)._1.get.format(lineFormatter)
        }
      }
    }

    private case class StructMemberStatement(member: StructMember)
        extends DeclarationBase(member.name, member.dataType, bounds = member.text)

    private case class MembersSection(members: Vector[StructMember],
                                      override val bounds: TextSource)
        extends InnerSection(bounds) {
      override val statements: Vector[Statement] = {
        members.map(StructMemberStatement)
      }
    }

    private case class KVStatement(id: String, expr: Expr, override val bounds: TextSource)
        extends BoundedStatement(bounds) {
      private val idToken = Literal.fromStart(id, bounds)
      private val delimToken = Literal.fromPrev(Symbols.KeyValueDelimiter, idToken)
      private val lhs = Vector(idToken, delimToken)
      private val rhs = buildExpression(expr)

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Adjacent(lhs), rhs))
      }
    }

    private case class MetadataSection(metaKV: Vector[MetaKV], override val bounds: TextSource)
        extends InnerSection(bounds) {
      override val statements: Vector[Statement] = {
        metaKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    private case class StructBlock(struct: TypeStruct)
        extends BlockStatement(Symbols.Struct, struct.text) {
      override def clause: Option[Span] = Some(
          Literal.fromPrev(struct.name, keywordLiteral)
      )

      override def body: Option[Statement] =
        Some(MembersSection(struct.members, struct.text))
    }

    private case class OutputsBlock(outputs: OutputSection)
        extends BlockStatement(Symbols.Output, outputs.text) {
      override def body: Option[Statement] =
        Some(
            DeclarationsSection(outputs.declarations)
        )
    }

    private case class MetaBlock(meta: MetaSection)
        extends BlockStatement(Symbols.Meta, meta.text) {
      override def body: Option[Statement] =
        Some(MetadataSection(meta.kvs, meta.text))
    }

    private case class ParameterMetaBlock(parameterMeta: ParameterMetaSection)
        extends BlockStatement(Symbols.ParameterMeta, parameterMeta.text) {
      override def body: Option[Statement] =
        Some(MetadataSection(parameterMeta.kvs, parameterMeta.text))
    }

    private def splitWorkflowElements(elements: Vector[WorkflowElement]): Vector[Statement] = {
      val statements: mutable.Buffer[Statement] = mutable.ArrayBuffer.empty
      val declarations: mutable.Buffer[Declaration] = mutable.ArrayBuffer.empty

      elements.foreach {
        case declaration: Declaration => declarations.append(declaration)
        case other =>
          if (declarations.nonEmpty) {
            statements.append(DeclarationsSection(declarations.toVector))
            declarations.clear()
          }
          statements.append(other match {
            case call: Call               => CallBlock(call)
            case scatter: Scatter         => ScatterBlock(scatter)
            case conditional: Conditional => ConditionalBlock(conditional)
            case other                    => throw new Exception(s"Unexpected workflow body element $other")
          })
      }

      if (declarations.nonEmpty) {
        statements.append(DeclarationsSection(declarations.toVector))
      }

      statements.toVector
    }

    private case class WorkflowElementBody(override val statements: Vector[Statement])
        extends OpenSection(emtpyLineBetweenStatements = true)

    private case class CallInputArgsContainer(args: Vector[Container])
        extends Container(args,
                          separator = s"${Symbols.ArrayDelimiter} ",
                          wrapping = Wrapping.Always) {
      require(args.nonEmpty)

      override def line: Int = args.head.line

      override def column: Int = args.head.column

      override def endLine: Int = args.last.endLine

      override def endColumn: Int = args.last.endColumn
    }

    private case class CallInputsStatement(inputs: CallInputs)
        extends BoundedStatement(inputs.text) {
      private val inputChunks =
        Literal.chainFromStart(Vector(Symbols.Input, Symbols.KeyValueDelimiter), inputs.text)
      private val argChunks = inputs.value.map { inp =>
        val nameToken = Literal.fromStart(inp.name, inp.text)
        val exprSpan = buildExpression(inp.expr)
        BoundSpacedContainer(
            Vector(nameToken, Literal.between(Symbols.Assignment, nameToken, exprSpan), exprSpan),
            bounds = inp.text
        )
      }

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(
            Vector(Adjacent(inputChunks), CallInputArgsContainer(argChunks))
        )
      }
    }

    private case class OpenSpacedContainer(items: Vector[Span],
                                           prefix: Option[Span] = None,
                                           suffix: Option[Span] = None)
        extends Container(items, separator = " ", prefix, suffix) {

      require(items.nonEmpty)

      override def line: Int = items.head.line

      override def endLine: Int = items.last.line

      override def column: Int = items.head.column

      override def endColumn: Int = items.last.endColumn
    }

    private case class CallBlock(call: Call) extends BlockStatement(Symbols.Call, call.text) {
      override def clause: Option[Span] = Some(
          if (call.alias.isDefined) {
            val alias = call.alias.get
            // assuming all parts of the clause are adjacent
            val tokens =
              Literal.chainFromPrev(Vector(call.name, Symbols.As, alias.name), keywordLiteral)
            OpenSpacedContainer(tokens)
          } else {
            Literal.fromPrev(call.name, keywordLiteral)
          }
      )

      override def body: Option[Statement] =
        if (call.inputs.isDefined) {
          Some(CallInputsStatement(call.inputs.get))
        } else {
          None
        }
    }

    private case class ScatterBlock(scatter: Scatter)
        extends BlockStatement(Symbols.Scatter, scatter.text) {

      override def clause: Option[Span] = {
        // assuming all parts of the clause are adjacent
        val openToken = Literal.fromPrev(Symbols.GroupOpen, keywordLiteral)
        val idToken = Literal.fromPrev(scatter.identifier, openToken)
        val inToken = Literal.fromPrev(Symbols.In, idToken)
        val exprAtom = buildExpression(scatter.expr)
        val closeToken = Literal.fromPrev(Symbols.GroupClose, exprAtom)
        Some(
            OpenSpacedContainer(
                Vector(idToken, inToken, exprAtom),
                prefix = Some(openToken),
                suffix = Some(closeToken)
            )
        )
      }

      override def body: Option[Statement] =
        Some(WorkflowElementBody(splitWorkflowElements(scatter.body)))
    }

    private case class ConditionalBlock(conditional: Conditional)
        extends BlockStatement(Symbols.If, conditional.text) {
      override def clause: Option[Span] = {
        val exprAtom = buildExpression(conditional.expr)
        val openToken = Literal.fromNext(Symbols.GroupOpen, exprAtom)
        val closeToken = Literal.fromPrev(Symbols.GroupClose, exprAtom)
        Some(
            OpenSpacedContainer(
                Vector(exprAtom),
                prefix = Some(openToken),
                suffix = Some(closeToken)
            )
        )
      }

      override def body: Option[Statement] =
        Some(WorkflowElementBody(splitWorkflowElements(conditional.body)))
    }

    private case class WorkflowSections(workflow: Workflow)
        extends InnerSection(workflow.text, emtpyLineBetweenStatements = true) {

      override val statements: Vector[Statement] = {
        val bodyElements = splitWorkflowElements(workflow.body)
        val (topSection, body) = if (workflow.input.isDefined) {
          if (bodyElements.nonEmpty && bodyElements.head.isInstanceOf[DeclarationsSection]) {
            val inputDecls = workflow.input.map(_.declarations.map(DeclarationStatement))
            (Some(
                 TopDeclarations(
                     inputDecls.get,
                     bodyElements.head.asInstanceOf[DeclarationsSection].statements,
                     bounds.line + 1
                 )
             ),
             bodyElements.tail)
          } else {
            (workflow.input.map(inp => InputsBlock(inp.declarations, inp.text)), bodyElements)
          }
        } else {
          (None, bodyElements)
        }
        val bodySection = if (body.nonEmpty) {
          Some(WorkflowElementBody(body))
        } else {
          None
        }
        Vector(
            topSection,
            bodySection,
            workflow.output.map(OutputsBlock),
            workflow.meta.map(
                MetaBlock
            ),
            workflow.parameterMeta.map(ParameterMetaBlock)
        ).flatten
      }
    }

    private case class WorkflowBlock(workflow: Workflow)
        extends BlockStatement(Symbols.Workflow, workflow.text) {

      override def clause: Option[Span] = Some(Literal.fromPrev(workflow.name, keywordLiteral))

      override def body: Option[Statement] = Some(WorkflowSections(workflow))
    }

    private case class CommandBlock(command: CommandSection)
        extends BoundedStatement(command.text) {
      private val commandStartRegexp = "^(.*)[\n\r]+(.*)".r
      private val commandEndRegexp = "\\s+$".r
      private val commandSingletonRegexp = "^(.*)[\n\r]*(.*?)\\s*$".r

      override def formatContents(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(
            Literal.chainFromStart(Vector(Symbols.Command, Symbols.CommandOpen), command.text)
        )
        if (command.parts.nonEmpty) {
          // The parser swallows anyting after the opening token ('{' or '<<<')
          // as part of the comment block, so we need to parse out any in-line
          // comment and append it separately
          val numParts = command.parts.size
          if (numParts == 1) {
            val (expr, comment) = command.parts.head match {
              case s: ValueString =>
                s.value match {
                  case commandSingletonRegexp(comment, body) =>
                    (ValueString(body, s.text), comment.trim)
                  case _ => (s, "")
                }
              case other => (other, "")
            }
            if (comment.nonEmpty && comment.startsWith(Symbols.Comment)) {
              lineFormatter.appendInlineComment(comment)
            }
            lineFormatter.endLine()
            val bodyFormatter = lineFormatter.derive(unspaced = true)
            bodyFormatter.beginLine()
            bodyFormatter.append(
                buildExpression(
                    expr,
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inStringOrCommand = true
                )
            )
            bodyFormatter.endLine()
          } else {
            val (expr, comment) = command.parts.head match {
              case s: ValueString =>
                s.value match {
                  case commandStartRegexp(comment, body) =>
                    (ValueString(body, s.text), comment.trim)
                  case _ => (s, "")
                }
              case other => (other, "")
            }
            if (comment.nonEmpty && comment.startsWith(Symbols.Comment)) {
              lineFormatter.appendInlineComment(comment)
            }
            lineFormatter.endLine()
            val bodyFormatter = lineFormatter.derive(unspaced = true)
            bodyFormatter.beginLine()
            bodyFormatter.append(
                buildExpression(
                    expr,
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inStringOrCommand = true
                )
            )
            if (numParts > 2) {
              command.parts.slice(1, command.parts.size - 1).foreach { chunk =>
                bodyFormatter.append(
                    buildExpression(chunk,
                                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                                    inStringOrCommand = true)
                )
              }
            }
            bodyFormatter.append(
                buildExpression(
                    command.parts.last match {
                      case ValueString(s, text) =>
                        ValueString(commandEndRegexp.replaceFirstIn(s, ""), text)
                      case other => other
                    },
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inStringOrCommand = true
                )
            )
            bodyFormatter.endLine()
          }
        }

        lineFormatter.beginLine()
        lineFormatter.append(Literal.fromEnd(Symbols.CommandClose, command.text))
      }
    }

    private case class RuntimeMetadataSection(runtimeKV: Vector[RuntimeKV],
                                              override val bounds: TextSource)
        extends InnerSection(bounds) {
      override val statements: Vector[Statement] = {
        runtimeKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    private case class RuntimeBlock(runtime: RuntimeSection)
        extends BlockStatement(Symbols.Runtime, runtime.text) {
      override def body: Option[Statement] =
        Some(RuntimeMetadataSection(runtime.kvs, runtime.text))
    }

    private case class TaskSections(task: Task)
        extends InnerSection(task.text, emtpyLineBetweenStatements = true) {

      override val statements: Vector[Statement] = {
        val otherDecls = task.declarations match {
          case v: Vector[Declaration] if v.nonEmpty => Some(DeclarationsSection(v))
          case _                                    => None
        }
        val (topSection, declSection) =
          if (task.input.isDefined && otherDecls.isDefined) {
            val inputDecls = task.input.map(_.declarations.map(DeclarationStatement))
            (Some(
                 TopDeclarations(
                     inputDecls.get,
                     otherDecls.get.statements,
                     line
                 )
             ),
             None)
          } else {
            (task.input.map(inp => InputsBlock(inp.declarations, inp.text)), otherDecls)
          }
        Vector(
            topSection,
            declSection,
            Some(CommandBlock(task.command)),
            task.output.map(OutputsBlock),
            task.runtime.map(RuntimeBlock),
            task.meta.map(MetaBlock),
            task.parameterMeta.map(ParameterMetaBlock)
        ).flatten
      }
    }

    private case class TaskBlock(task: Task) extends BlockStatement(Symbols.Task, task.text) {
      override def clause: Option[Span] =
        Some(Literal.fromPrev(task.name, keywordLiteral))

      override def body: Option[Statement] = Some(TaskSections(task))
    }

    private case class DocumentSections(document: Document)
        extends Section(emtpyLineBetweenStatements = true, topCommentsAllowed = false)
        with BoundMultiline {

      override def bounds: TextSource = document.text

      override val statements: Vector[Statement] = {
        val version = Vector(VersionStatement(document.version))
        val imports = document.elements.collect { case imp: ImportDoc => imp } match {
          case v: Vector[ImportDoc] if v.nonEmpty => Vector(ImportsSection(v))
          case _                                  => Vector.empty
        }
        val structs = document.elements.collect {
          case struct: TypeStruct => StructBlock(struct)
        }
        val workflow = document.workflow.map(WorkflowBlock).toVector
        val tasks = document.elements.collect {
          case task: Task => TaskBlock(task)
        }
        version ++ imports ++ structs ++ workflow ++ tasks
      }
    }

    def apply(): Vector[String] = {
      val documentSections = DocumentSections(document)

      val inlineComments = commentLines.toMap
        .map {
          case (line, text) => text -> document.comments.get(line)
        }
        .groupBy(_._1)
        .map {
          case (k, v) => (k, v.values.toVector.flatten)
        }

      val lineFormatter = LineFormatter(inlineComments)

      documentSections.format(lineFormatter)

      lineFormatter.toVector
    }
  }

  def formatDocument(document: Document): Vector[String] = {
    FormatterDocument(document).apply()
  }

  def formatDocuments(url: URL): Unit = {
    Parsers(opts).getDocumentWalker[Vector[String]](url, documents).walk { (url, doc, results) =>
      results(url) = formatDocument(doc)
    }
  }
}
