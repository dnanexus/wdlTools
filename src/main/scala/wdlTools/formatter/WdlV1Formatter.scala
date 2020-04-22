package wdlTools.formatter

import java.net.URL

import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Comment, Parsers, TextSource}
import wdlTools.util.{Options, Util, Verbosity}

import scala.collection.mutable
import scala.math.Integral.Implicits._

case class WdlV1Formatter(opts: Options,
                          documents: mutable.Map[URL, Vector[String]] = mutable.Map.empty) {

  case class FormatterDocument(document: Document) {
    private val commentLines: mutable.Map[Int, TextSource] = mutable.HashMap.empty

    def getTextSourceFromChunks(chunks: Vector[Chunk]): TextSource = {
      TextSource.fromSpan(chunks.head.textSource, chunks.last.textSource)
    }

    def getCommentsBetween(start: Int, stop: Int): Vector[Comment] = {
      document.comments.filterKeys(i => i >= start && i <= stop).values.toVector
    }

    def getCommentsBetween(before: TextSource,
                           after: TextSource,
                           startInclusive: Boolean = false,
                           stopInclusive: Boolean = false): Vector[Comment] = {
      val start = before.endLine - (if (startInclusive) 0 else 1)
      val stop = after.line + (if (stopInclusive) 0 else 1)
      getCommentsBetween(start, stop)
    }

    def getCommentsWithin(textSource: TextSource,
                          startInclusive: Boolean = true,
                          stopInclusive: Boolean = false): Vector[Comment] = {
      val start = textSource.line + (if (startInclusive) 0 else 1)
      val stop = textSource.endLine - (if (stopInclusive) 0 else 1)
      getCommentsBetween(start, stop)
    }

    def getCommentAt(line: Int): Option[Comment] = {
      document.comments.get(line)
    }

    abstract class Atom extends Chunk {
      // add this Atom to the index - this is used by the formatter to add in-line comments
      textSource.lineRange.foreach { line =>
        if (!commentLines.contains(line) || commentLines(line).maxCol < textSource.maxCol) {
          commentLines(line) = textSource
        }
      }

      def length: Int

      def format(lineFormatter: LineFormatter): Unit = {
        val space = if (lineFormatter.atLineStart) {
          ""
        } else {
          " "
        }
        if (lineFormatter.lengthRemaining < space.length + this.length) {
          lineFormatter.endLine(wrap = true)
          lineFormatter.appendChunk(this)
        } else {
          lineFormatter.appendString(space)
          lineFormatter.appendChunk(this)
        }
      }
    }

    case class Token(value: String, override val textSource: TextSource) extends Atom {
      override def length: Int = value.length

      override lazy val toString: String = value
    }

    object Token {
      def fromStart(value: String, start: TextSource): Token = {
        Token(value, start.copy(endLine = start.line, endCol = start.col + value.length))
      }

      def fromStop(value: String, stop: TextSource): Token = {
        Token(value, stop.copy(line = stop.endLine, col = stop.endCol - value.length))
      }

      def fromBetween(value: String,
                      before: TextSource,
                      after: TextSource,
                      spacing: Int = 1,
                      preferBefore: Boolean = false): Token = {
        val textSource = if (before.endLine == after.line) {
          val line = before.endLine
          val beforeCol = before.endCol
          val afterCol = after.col
          val diff = afterCol - beforeCol
          val col = if (diff > value.length) {
            // assume the value is half-way between before and after
            val (quot, rem) = (diff - value.length) /% 2
            if (rem > 0 && preferBefore) {
              beforeCol + quot + rem
            } else {
              beforeCol + quot
            }
          } else {
            beforeCol
          }
          TextSource(line, col, line, col + value.length)
        } else if (preferBefore) {
          // assume the value is separated by the previous token by one space
          TextSource(before.endLine,
                     before.endCol + spacing,
                     before.endLine,
                     before.endCol + value.length + spacing)
        } else {
          TextSource(after.line, after.col - spacing, after.col, after.col - value.length - spacing)
        }
        Token(value, textSource)
      }

      def fromPrev(value: String, prev: TextSource, spacing: Int = 0): Token = {
        val textSource = TextSource(
            prev.endLine,
            prev.endCol + spacing,
            prev.endLine,
            prev.endCol + value.length + spacing
        )
        Token(value, textSource)
      }

      def fromNext(value: String, next: TextSource): Token = {
        val textSource = TextSource(
            next.line,
            next.col - value.length,
            next.line,
            next.col
        )
        Token(value, textSource)
      }

      def chain(values: Vector[String], start: TextSource, spacing: Int = 0): Vector[Token] = {
        var prev = start
        values.map { value =>
          val token = Token.fromPrev(value, prev, spacing)
          prev = token.textSource
          token
        }
      }
    }

    case class StringLiteral(value: Any, override val textSource: TextSource) extends Atom {
      override lazy val toString: String = s"${'"'}${value}${'"'}"

      override def length: Int = toString.length
    }

    object StringLiteral {
      def fromStart(value: String, start: TextSource): StringLiteral = {
        StringLiteral(value, start.copy(endLine = start.line, endCol = start.col + value.length))
      }

      def fromPrev(value: String, prev: TextSource, spacing: Int = 1): StringLiteral = {
        val textSource = TextSource(
            prev.endLine,
            prev.endCol + spacing,
            prev.endLine,
            prev.endCol + value.length + spacing
        )
        StringLiteral(value, textSource)
      }
    }

    abstract class AtomSequence(atoms: Vector[Atom]) extends Atom {
      override val textSource: TextSource = getTextSourceFromChunks(atoms)
    }

    /**
      * A sequence of adjacent atoms (with no spacing or wrapping)
      *
      * @param atoms the atoms
      */
    case class Adjacent(atoms: Vector[Atom]) extends AtomSequence(atoms) {
      override lazy val toString: String = {
        atoms.mkString("")
      }

      override lazy val length: Int = {
        atoms.map(_.length).sum
      }
    }

    abstract class Group(prefix: Option[Atom] = None,
                         suffix: Option[Atom] = None,
                         override val wrapAll: Boolean = false)
        extends Atom {
      protected lazy val prefixLength: Int = prefix.map(_.length).getOrElse(0)
      protected lazy val suffixLength: Int = suffix.map(_.length).getOrElse(0)

      override def format(lineFormatter: LineFormatter): Unit = {
        val wrapAndIndent = if (prefix.isEmpty) {
          lineFormatter.endLine(wrap = true, Indenting.IfNotIndented)
          false
        } else if (prefixLength < lineFormatter.lengthRemaining) {
          lineFormatter.appendChunk(prefix.get)
          true
        } else {
          lineFormatter.endLine(wrap = true, Indenting.IfNotIndented)
          val tooLong = length > lineFormatter.lengthRemaining
          lineFormatter.appendChunk(prefix.get)
          wrapAll || tooLong
        }

        if (wrapAndIndent) {
          lineFormatter.endLine()
          wrapBody(lineFormatter.indented())
          lineFormatter.beginLine()
        }

        if (suffix.isDefined) {
          if (suffixLength > lineFormatter.lengthRemaining) {
            lineFormatter.endLine(wrap = true)
          }
          lineFormatter.appendChunk(suffix.get)
        }
      }

      def wrapBody(lineFormatter: LineFormatter): Unit
    }

    abstract class Container(items: Vector[Atom],
                             prefix: Option[Atom] = None,
                             suffix: Option[Atom] = None,
                             wrapping: Wrapping = Wrapping.AsNeeded,
                             override val textSource: TextSource)
        extends Group(prefix = prefix, suffix = suffix, wrapAll = wrapping == Wrapping.Always) {

      def separator: String

      private lazy val itemStr: String = items.mkString(separator)
      private val itemLength: Int = itemStr.length
      private val itemTokens: Vector[Atom] = items.zipWithIndex.map {
        case (item, i) if i < item.length - 1 =>
          val delimiterToken = Token.fromPrev(separator, item.textSource)
          Adjacent(Vector(item, delimiterToken))
        case (item, _) => item
      }

      override lazy val toString: String = {
        val open = prefix.map(_.toString).getOrElse("")
        val close = suffix.map(_.toString).getOrElse("")
        s"${open}${itemStr}${close}"
      }

      override def length: Int = {
        itemLength + prefixLength + suffixLength
      }

      def wrapBody(lineFormatter: LineFormatter): Unit = {
        if (items.nonEmpty) {
          if (wrapAll || (items.length > 1 && itemLength > lineFormatter.lengthRemaining)) {
            lineFormatter.beginLine()
            lineFormatter.appendAll(itemTokens, wrapping = Wrapping.Always)
            lineFormatter.endLine()
          } else {
            lineFormatter.appendAll(itemTokens, wrapping = wrapping)
          }
        }
      }
    }

    case class DelimitedContainer(items: Vector[Atom],
                                  prefix: Option[Atom] = None,
                                  suffix: Option[Atom] = None,
                                  wrapping: Wrapping = Wrapping.AsNeeded,
                                  override val textSource: TextSource)
        extends Container(items, prefix, suffix, wrapping, textSource) {
      override val separator: String = s"${Symbols.ArrayDelimiter} "
    }

    case class SpacedContainer(items: Vector[Atom],
                               prefix: Option[Atom] = None,
                               suffix: Option[Atom] = None,
                               wrapping: Wrapping = Wrapping.Never,
                               override val textSource: TextSource)
        extends Container(items, prefix, suffix, wrapping, textSource) {
      override val separator: String = " "
    }

    case class KeyValue(key: Atom,
                        value: Atom,
                        delimiter: String = Symbols.KeyValueDelimiter,
                        override val textSource: TextSource)
        extends Atom {
      private val delimiterToken: Token = Token.fromPrev(delimiter, key.textSource)

      override lazy val toString: String = {
        s"${key}${delimiter} ${value}"
      }

      override def length: Int = {
        key.length + value.length + delimiter.length + 1
      }

      override def format(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Adjacent(Vector(key, delimiterToken)), value))
      }
    }

    object DataType {
      def buildDataType(name: String,
                        inner1: Option[Atom] = None,
                        inner2: Option[Atom] = None,
                        quantifier: Option[String] = None,
                        textSource: TextSource): Atom = {
        val nameToken: Token = Token.fromStart(name, textSource)
        val quantifierToken: Option[Token] = quantifier.map(sym => Token.fromStop(sym, textSource))
        if (inner1.isDefined) {
          // making the assumption that the open token comes directly after the name
          val openToken = Token.fromPrev(Symbols.TypeParamOpen, nameToken.textSource)
          // making the assumption that the close token comes directly before the quantifier (if any)
          val closeToken = Token.fromStop(Symbols.TypeParamClose,
                                          quantifierToken.map(_.textSource).getOrElse(textSource))
          DelimitedContainer(
              Vector(inner1, inner2).flatten,
              prefix = Some(Adjacent(Vector(nameToken, openToken))),
              suffix = Some(Adjacent(Vector(Some(closeToken), quantifierToken).flatten)),
              textSource = textSource
          )
        } else if (quantifier.isDefined) {
          Adjacent(Vector(nameToken, quantifierToken.get))
        } else {
          nameToken
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

      def fromWdlType(wdlType: Type, quantifier: Option[Token] = None): Atom = {
        wdlType match {
          case TypeOptional(inner, text) =>
            fromWdlType(inner, quantifier = Some(Token.fromStop(Symbols.Optional, text)))
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
          case TypeStruct(name, _, text) => Token(name, text)
          case TypeObject(text)          => Token(Symbols.ObjectType, text)
          case TypeString(text)          => Token(Symbols.StringType, text)
          case TypeBoolean(text)         => Token(Symbols.BooleanType, text)
          case TypeInt(text)             => Token(Symbols.IntType, text)
          case TypeFloat(text)           => Token(Symbols.FloatType, text)
          case other                     => throw new Exception(s"Unrecognized type $other")
        }
      }
    }

    case class Unirary(oper: String, value: Atom, override val textSource: TextSource)
        extends Atom {
      override lazy val toString: String = s"${oper}${value}"

      override def length: Int = oper.length + value.length
    }

    case class Operation(oper: String,
                         lhs: Atom,
                         rhs: Atom,
                         grouped: Boolean = false,
                         override val textSource: TextSource)
        extends Group(prefix = if (grouped) {
          Some(Token.fromStart(Symbols.GroupOpen, textSource))
        } else {
          None
        }, suffix = if (grouped) {
          Some(Token.fromStop(Symbols.GroupClose, textSource))
        } else {
          None
        }, wrapAll = false) {

      private val operToken = Token.fromBetween(oper, lhs.textSource, rhs.textSource)

      override lazy val toString: String = {
        val str = s"${lhs} ${oper} ${rhs}"
        if (grouped) {
          s"${Symbols.GroupOpen}${str}${Symbols.GroupClose}"
        } else {
          str
        }
      }

      override def length: Int = {
        val parenLength = if (grouped) {
          2
        } else {
          0
        }
        lhs.length + oper.length + rhs.length + 2 + parenLength
      }

      def wrapBody(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(lhs, operToken, rhs), Wrapping.AsNeeded)
      }
    }

    case class Placeholder(value: Atom,
                           open: String = Symbols.PlaceholderOpenDollar,
                           close: String = Symbols.PlaceholderClose,
                           options: Option[Vector[Atom]] = None,
                           textSource: TextSource)
        extends Group(prefix = Some(Token.fromStart(open, textSource)),
                      suffix = Some(Token.fromStop(close, textSource)),
                      wrapAll = false) {

      override lazy val toString: String = {
        val optionsStr = options
          .map(_.map { opt =>
            s"${opt} "
          }.mkString)
          .getOrElse("")
        s"${open}${optionsStr}${value}${close}"
      }

      override def length: Int = {
        value.length + open.length + close.length + options
          .map(_.map(_.length + 1).sum)
          .getOrElse(0)
      }

      override def wrapBody(lineFormatter: LineFormatter): Unit = {
        if (options.isDefined) {
          lineFormatter.appendAll(options.get)
        }
        lineFormatter.appendChunk(value)
      }
    }

    def buildExpression(
        expr: Expr,
        placeholderOpen: String = Symbols.PlaceholderOpenDollar,
        inString: Boolean = false,
        inPlaceholder: Boolean = false,
        inOperation: Boolean = false
    ): Atom = {

      /**
        * Creates a Token or a StringLiteral, depending on whether we're already inside a string literal
        *
        * @param value the value to wrap
        * @return an Atom
        */
      def stringOrToken(value: String, textSource: TextSource): Atom = {
        if (inString && !inPlaceholder) {
          Token(value, textSource)
        } else {
          StringLiteral(value, textSource)
        }
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
        * @return an Atom
        */
      def nested(nestedExpression: Expr,
                 placeholderOpen: String = placeholderOpen,
                 inString: Boolean = inString,
                 inPlaceholder: Boolean = inPlaceholder,
                 inOperation: Boolean = inOperation): Atom = {
        buildExpression(nestedExpression,
                        placeholderOpen = placeholderOpen,
                        inString = inString,
                        inPlaceholder = inPlaceholder,
                        inOperation = inOperation)
      }

      def unirary(oper: String, value: Expr, textSource: TextSource): Atom = {
        Unirary(oper, nested(value, inOperation = true), textSource)
      }

      def operation(oper: String, lhs: Expr, rhs: Expr, textSource: TextSource): Atom = {
        Operation(oper,
                  nested(lhs, inPlaceholder = inString, inOperation = true),
                  nested(rhs, inPlaceholder = inString, inOperation = true),
                  grouped = inOperation,
                  textSource)
      }

      def option(name: String, value: Expr): Atom = {
        val eqToken = Token.fromNext(Symbols.Assignment, value.text)
        val nameToken = Token.fromNext(name, eqToken.textSource)
        Adjacent(Vector(nameToken, eqToken, nested(value, inPlaceholder = true)))
      }

      expr match {
        // literal values
        case ValueNull(text)           => Token(Symbols.Null, text)
        case ValueString(value, text)  => stringOrToken(value, text)
        case ValueFile(value, text)    => stringOrToken(value, text)
        case ValueBoolean(value, text) => Token(value.toString, text)
        case ValueInt(value, text)     => Token(value.toString, text)
        case ValueFloat(value, text)   => Token(value.toString, text)
        case ExprPair(left, right, text) if !(inString || inPlaceholder) =>
          DelimitedContainer(
              Vector(nested(left), nested(right)),
              prefix = Some(Token.fromStart(Symbols.GroupOpen, text)),
              suffix = Some(Token.fromStop(Symbols.GroupClose, text)),
              textSource = text
          )
        case ExprArray(value, text) =>
          DelimitedContainer(
              value.map(nested(_)),
              prefix = Some(Token.fromStart(Symbols.ArrayLiteralOpen, text)),
              suffix = Some(Token.fromStop(Symbols.ArrayLiteralClose, text)),
              textSource = text
          )
        case ExprMap(value, text) =>
          DelimitedContainer(
              value.map {
                case ExprMapItem(k, v, itemText) =>
                  KeyValue(nested(k), nested(v), textSource = itemText)
              },
              prefix = Some(Token.fromStart(Symbols.MapOpen, text)),
              suffix = Some(Token.fromStop(Symbols.MapClose, text)),
              wrapping = Wrapping.Always,
              textSource = text
          )
        case ExprObject(value, text) =>
          DelimitedContainer(
              value.map {
                case ExprObjectMember(k, v, memberText) =>
                  KeyValue(Token.fromStart(k, memberText), nested(v), textSource = memberText)
              },
              prefix = Some(Token.fromStart(Symbols.ObjectOpen, text)),
              suffix = Some(Token.fromStop(Symbols.ObjectClose, text)),
              wrapping = Wrapping.Always,
              textSource = text
          )
        // placeholders
        case ExprPlaceholderEqual(t, f, value, text) =>
          Placeholder(nested(value, inPlaceholder = true),
                      placeholderOpen,
                      options = Some(
                          Vector(
                              option(Symbols.TrueOption, t),
                              option(Symbols.FalseOption, f)
                          )
                      ),
                      textSource = text)
        case ExprPlaceholderDefault(default, value, text) =>
          Placeholder(nested(value, inPlaceholder = true),
                      placeholderOpen,
                      options = Some(Vector(option(Symbols.DefaultOption, default))),
                      textSource = text)
        case ExprPlaceholderSep(sep, value, text) =>
          Placeholder(nested(value, inPlaceholder = true),
                      placeholderOpen,
                      options = Some(Vector(option(Symbols.SepOption, sep))),
                      textSource = text)
        case ExprCompoundString(value, text) if !inPlaceholder =>
          val atom = Adjacent(value.map(nested(_, inString = true)))
          if (inString) {
            atom
          } else {
            StringLiteral(atom, text)
          }
        // other expressions need to be wrapped in a placeholder if they
        // appear in a string or command block
        case other =>
          val atom = other match {
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
            case ExprIdentifier(id, text)      => Token(id, text)
            case ExprAt(array, index, text) =>
              DelimitedContainer(
                  Vector(nested(index, inPlaceholder = inString)),
                  prefix = Some(
                      Adjacent(
                          Vector(nested(array, inPlaceholder = inString),
                                 Token.fromPrev(Symbols.IndexOpen, array.text))
                      )
                  ),
                  suffix = Some(Token.fromStop(Symbols.IndexClose, text)),
                  textSource = text
              )
            case ExprIfThenElse(cond, tBranch, fBranch, text) =>
              SpacedContainer(
                  Vector(
                      Token.fromStart(Symbols.If, text),
                      nested(cond, inOperation = true, inPlaceholder = inString),
                      Token.fromBetween(Symbols.Then, cond.text, tBranch.text),
                      nested(tBranch, inOperation = true, inPlaceholder = inString),
                      Token.fromBetween(Symbols.Else, tBranch.text, fBranch.text),
                      nested(fBranch, inOperation = true, inPlaceholder = inString)
                  ),
                  wrapping = Wrapping.AsNeeded,
                  textSource = text
              )
            case ExprApply(funcName, elements, text) =>
              DelimitedContainer(
                  elements.map(nested(_, inPlaceholder = inString)),
                  prefix =
                    Some(Adjacent(Token.chain(Vector(funcName, Symbols.FunctionCallOpen), text))),
                  suffix = Some(Token.fromStop(Symbols.FunctionCallClose, text)),
                  textSource = text
              )
            case ExprGetName(e, id, text) =>
              val idToken = Token.fromStop(id, text)
              Adjacent(
                  Vector(
                      nested(e, inPlaceholder = inString),
                      Token.fromBetween(Symbols.Access, e.text, idToken.textSource, spacing = 0),
                      idToken
                  )
              )
            case other => throw new Exception(s"Unrecognized expression $other")
          }
          if (inString && !inPlaceholder) {
            Placeholder(atom, placeholderOpen, textSource = other.text)
          } else {
            atom
          }
      }
    }

    /**
      * Marker base class for Statements.
      */
    abstract class Statement extends Chunk {
      override def format(lineFormatter: LineFormatter): Unit = {
        lineFormatter.beginLine()
        formatChunks(lineFormatter)
        lineFormatter.endLine()
      }

      def formatChunks(lineFormatter: LineFormatter): Unit
    }

    abstract class StatementGroup extends Statement {
      def statements: Vector[Statement]

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        statements.foreach { stmt =>
          stmt.format(lineFormatter)
        }
      }

      override val textSource: TextSource = getTextSourceFromChunks(statements)
    }

    abstract class SectionsStatement extends Statement {
      def sections: Vector[Statement]

      def topCommentsAllowed: Boolean = true

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        val comments: Vector[Comment] = getCommentsWithin(textSource)

        if (comments.nonEmpty) {
          if (sections.nonEmpty) {
            val sortedSources = sections.map(_.textSource).sortWith(_ < _)
            val top = textSource.copy(endLine = sortedSources.head.line)
            val bottom = textSource.copy(line = sortedSources.last.endLine)

            // Mapping of section TextSource to (before?, emptyLineBetween?, comments)
            val sectionToComment: Map[TextSource, (Boolean, Boolean, Vector[Comment])] =
              if (comments.nonEmpty) {
                // A line comment sticks with the closest statement (before or after) and defaults
                // to the next statement if it is equidistant to both. To do this, we first need to
                // sort the sections by their original order.
                (Vector(top) ++ sortedSources ++ Vector(bottom))
                  .sliding(2)
                  .flatMap {
                    case Vector(l, r) =>
                      val comments = getCommentsBetween(l, r)
                      if (comments.nonEmpty) {
                        val beforeDist = comments.head.text.line - l.endLine + 1
                        val afterDist = r.line - comments.last.text.endLine
                        if (!topCommentsAllowed && l == top) {
                          Some(r -> (true, true, comments))
                        } else if (afterDist <= beforeDist) {
                          Some(r -> (true, afterDist > 1, comments))
                        } else {
                          Some(l -> (false, beforeDist > 1, comments))
                        }
                      } else {
                        None
                      }
                    case _ => None
                  }
                  .toMap
              } else {
                Map.empty
              }

            def addSection(section: Statement): Unit = {
              if (sectionToComment.contains(section.textSource)) {
                val (isBefore, emptyLine, sectionComments) = sectionToComment(section.textSource)
                if (isBefore) {
                  lineFormatter.appendLineComments(sectionComments)
                  if (emptyLine) {
                    lineFormatter.emptyLine()
                  }
                  section.format(lineFormatter)
                } else {
                  section.format(lineFormatter)
                  if (emptyLine) {
                    lineFormatter.emptyLine()
                  }
                  lineFormatter.appendLineComments(sectionComments)
                }
              } else {
                section.format(lineFormatter)
              }
            }

            if (sectionToComment.contains(top)) {
              lineFormatter.appendLineComments(sectionToComment(top)._3)
              lineFormatter.emptyLine()
            }

            addSection(sections.head)
            sections.tail.foreach { section =>
              lineFormatter.emptyLine()
              addSection(section)
            }

            if (sectionToComment.contains(bottom)) {
              lineFormatter.emptyLine()
              lineFormatter.appendLineComments(sectionToComment(bottom)._3)
            }
          } else {
            lineFormatter.appendLineComments(comments)
          }
        } else if (sections.nonEmpty) {
          sections.head.format(lineFormatter)
          sections.tail.foreach { section =>
            lineFormatter.emptyLine()
            section.format(lineFormatter)
          }
        }
      }

      override val textSource: TextSource = {
        if (sections.nonEmpty) {
          val sortedSources = sections.map(_.textSource).sortWith(_ < _)
          TextSource.fromSpan(sortedSources.head, sortedSources.last)
        } else {
          TextSource.empty
        }
      }
    }

    class Sections extends SectionsStatement {
      val statements: mutable.Buffer[Statement] = mutable.ArrayBuffer.empty

      lazy override val sections: Vector[Statement] = statements.toVector
    }

    case class VersionStatement(version: Version) extends Statement {
      override def textSource: TextSource = version.text

      private val keywordToken = Token.fromStart(Symbols.Version, version.text)
      private val versionToken = Token.fromStop(version.value.name, version.text)

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(keywordToken, versionToken), Wrapping.Never)
      }
    }

    case class ImportStatement(importDoc: ImportDoc) extends Statement {
      override def textSource: TextSource = importDoc.text

      private val keywordToken = Token.fromStart(Symbols.Import, importDoc.text)
      // assuming URL comes directly after keyword
      private val urlLiteral = StringLiteral.fromPrev(importDoc.addr.value, keywordToken.textSource)
      // assuming namespace comes directly after url
      private val nameTokens = importDoc.name.map { name =>
        Token.chain(Vector(Symbols.As, name.value), urlLiteral.textSource, spacing = 1)
      }
      private val aliasTokens = importDoc.aliases.map { alias =>
        Token.chain(Vector(Symbols.Alias, alias.id1, Symbols.As, alias.id2),
                    alias.text,
                    spacing = 1)
      }

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(keywordToken, urlLiteral), Wrapping.Never)
        if (nameTokens.isDefined) {
          lineFormatter.appendAll(nameTokens.get, Wrapping.AsNeeded)
        }
        aliasTokens.foreach { alias =>
          lineFormatter.appendAll(alias, Wrapping.Always)
        }
      }
    }

    case class ImportsSection(imports: Vector[ImportDoc]) extends StatementGroup {
      override val statements: Vector[Statement] = {
        imports.map(ImportStatement)
      }
    }

    abstract class DeclarationBase(name: String,
                                   wdlType: Type,
                                   expr: Option[Expr] = None,
                                   override val textSource: TextSource)
        extends Statement {

      private val typeAtom = DataType.fromWdlType(wdlType)
      // assuming name follows direclty after type
      private val nameToken = Token.fromPrev(name, typeAtom.textSource, spacing = 1)
      private val lhs = Vector(typeAtom, nameToken)
      private val rhs = expr.map { e =>
        val eqToken = Token.fromPrev(Symbols.Assignment, nameToken.textSource, spacing = 1)
        val exprAtom = buildExpression(e)
        Vector(eqToken, exprAtom)
      }

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(lhs)
        if (rhs.isDefined) {
          lineFormatter.appendAll(rhs.get)
        }
      }
    }

    case class DeclarationStatement(decl: Declaration)
        extends DeclarationBase(decl.name, decl.wdlType, decl.expr, decl.text)

    case class StructMemberStatement(member: StructMember)
        extends DeclarationBase(member.name, member.dataType, textSource = member.text)

    case class MembersSection(members: Vector[StructMember]) extends StatementGroup {
      override val statements: Vector[Statement] = {
        members.map(StructMemberStatement)
      }
    }

    case class DeclarationsSection(declarations: Vector[Declaration]) extends StatementGroup {
      override val statements: Vector[Statement] = {
        declarations.map(DeclarationStatement)
      }
    }

    case class KVStatement(id: String, expr: Expr, override val textSource: TextSource)
        extends Statement {
      private val idToken = Token.fromStart(id, textSource)
      private val delimToken = Token.fromPrev(Symbols.KeyValueDelimiter, idToken.textSource)
      private val lhs = Vector(idToken, delimToken)
      private val rhs = buildExpression(expr)

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Adjacent(lhs), rhs))
      }
    }

    case class MetadataSection(metaKV: Vector[MetaKV]) extends StatementGroup {
      override val statements: Vector[Statement] = {
        metaKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    sealed abstract class BlockStatement(keyword: String, override val textSource: TextSource)
        extends Statement {

      def clause: Option[Chunk] = None

      def body: Option[Chunk] = None

      protected val keywordToken: Token = Token.fromStart(keyword, textSource)
      private val clauseChunk: Option[Chunk] = clause
      // assume the open brace is on the same line as the keyword/clause
      private val openToken =
        Token.fromPrev(Symbols.BlockOpen,
                       clauseChunk.getOrElse(keywordToken).textSource,
                       spacing = 1)
      private val bodyChunk: Option[Chunk] = body
      private val closeToken = Token.fromStop(Symbols.BlockClose, textSource)

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(Vector(Some(keywordToken), clauseChunk, Some(openToken)).flatten)
        if (body.isDefined) {
          lineFormatter.endLine()
          bodyChunk.get.format(lineFormatter.indented())
          lineFormatter.beginLine()
        }
        lineFormatter.appendChunk(closeToken)
      }
    }

    case class StructBlock(struct: TypeStruct) extends BlockStatement(Symbols.Struct, struct.text) {
      override val clause: Option[Atom] = Some(
          Token.fromPrev(struct.name, keywordToken.textSource, spacing = 1)
      )

      override val body: Option[Chunk] = Some(MembersSection(struct.members))
    }

    case class InputsBlock(inputs: InputSection)
        extends BlockStatement(Symbols.Input, inputs.text) {
      override val body: Option[Chunk] = Some(DeclarationsSection(inputs.declarations))
    }

    case class OutputsBlock(outputs: OutputSection)
        extends BlockStatement(Symbols.Output, outputs.text) {
      override val body: Option[Chunk] = Some(DeclarationsSection(outputs.declarations))
    }

    case class MetaBlock(meta: MetaSection) extends BlockStatement(Symbols.Meta, meta.text) {
      override val body: Option[Chunk] = Some(MetadataSection(meta.kvs))
    }

    case class ParameterMetaBlock(parameterMeta: ParameterMetaSection)
        extends BlockStatement(Symbols.ParameterMeta, parameterMeta.text) {
      override val body: Option[Chunk] = Some(MetadataSection(parameterMeta.kvs))
    }

    case class WorkflowElementBody(elements: Vector[WorkflowElement]) extends SectionsStatement {
      override val sections: Vector[Statement] = {
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
    }

    case class CallInputsStatement(inputs: CallInputs) extends Statement {
      private val inputChunks =
        Token.chain(Vector(Symbols.Input, Symbols.KeyValueDelimiter), inputs.text)
      private val argChunks = inputs.value.map { inp =>
        val nameToken = Token.fromStart(inp.name, inp.text)
        SpacedContainer(
            Vector(nameToken,
                   Token.fromBetween(Symbols.Assignment, nameToken.textSource, inp.expr.text),
                   buildExpression(inp.expr)),
            textSource = inp.text
        )
      }

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(
            Vector(Adjacent(inputChunks),
                   DelimitedContainer(argChunks, textSource = getTextSourceFromChunks(argChunks)))
        )
      }

      override def textSource: TextSource = inputs.text
    }

    case class CallBlock(call: Call) extends BlockStatement(Symbols.Call, call.text) {
      override val clause: Option[Atom] = Some(
          if (call.alias.isDefined) {
            val alias = call.alias.get
            // assuming all parts of the clause are adjacent
            val tokens = Token.chain(Vector(call.name, Symbols.As, alias.name),
                                     keywordToken.textSource,
                                     spacing = 1)
            SpacedContainer(tokens, textSource = getTextSourceFromChunks(tokens))
          } else {
            Token.fromPrev(call.name, keywordToken.textSource, spacing = 1)
          }
      )

      override val body: Option[Chunk] = if (call.inputs.isDefined) {
        Some(CallInputsStatement(call.inputs.get))
      } else {
        None
      }
    }

    case class ScatterBlock(scatter: Scatter)
        extends BlockStatement(Symbols.Scatter, scatter.text) {

      override val clause: Option[Atom] = {
        // assuming all parts of the clause are adjacent
        val openToken = Token.fromPrev(Symbols.GroupOpen, keywordToken.textSource, spacing = 1)
        val idToken = Token.fromPrev(scatter.identifier, openToken.textSource)
        val inToken = Token.fromPrev(Symbols.In, idToken.textSource, spacing = 1)
        val exprAtom = buildExpression(scatter.expr)
        val closeToken = Token.fromPrev(Symbols.GroupClose, exprAtom.textSource)
        Some(
            SpacedContainer(
                Vector(idToken, inToken, exprAtom),
                prefix = Some(openToken),
                suffix = Some(closeToken),
                textSource = TextSource.fromSpan(openToken.textSource, closeToken.textSource)
            )
        )
      }

      override val body: Option[Chunk] = Some(WorkflowElementBody(scatter.body))
    }

    case class ConditionalBlock(conditional: Conditional)
        extends BlockStatement(Symbols.If, conditional.text) {
      override val clause: Option[Atom] = {
        val exprAtom = buildExpression(conditional.expr)
        val openToken = Token.fromNext(Symbols.GroupOpen, exprAtom.textSource)
        val closeToken = Token.fromPrev(Symbols.GroupClose, exprAtom.textSource)
        Some(
            SpacedContainer(
                Vector(exprAtom),
                prefix = Some(openToken),
                suffix = Some(closeToken),
                textSource = TextSource.fromSpan(openToken.textSource, closeToken.textSource)
            )
        )
      }

      override val body: Option[Chunk] = Some(WorkflowElementBody(conditional.body))
    }

    case class WorkflowSections(workflow: Workflow) extends Sections {
      if (workflow.input.isDefined) {
        statements.append(InputsBlock(workflow.input.get))
      }

      statements.append(WorkflowElementBody(workflow.body))

      if (workflow.output.isDefined) {
        statements.append(OutputsBlock(workflow.output.get))
      }

      if (workflow.meta.isDefined) {
        statements.append(MetaBlock(workflow.meta.get))
      }

      if (workflow.parameterMeta.isDefined) {
        statements.append(ParameterMetaBlock(workflow.parameterMeta.get))
      }
    }

    case class WorkflowBlock(workflow: Workflow)
        extends BlockStatement(Symbols.Workflow, workflow.text) {

      override val clause: Option[Atom] =
        Some(Token.fromPrev(workflow.name, keywordToken.textSource, spacing = 1))

      override val body: Option[Chunk] = Some(WorkflowSections(workflow))
    }

    case class CommandBlock(command: CommandSection) extends Statement {
      private val commandStartRegexp = "^(.*)[\n\r]+(.*)".r
      private val commandEndRegexp = "\\s+$".r
      private val commandSingletonRegexp = "^(.*)[\n\r]*(.*?)\\s*$".r

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendAll(
            Token.chain(Vector(Symbols.Command, Symbols.CommandOpen), command.text, spacing = 1)
        )
        if (command.parts.nonEmpty) {
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
            val bodyFormatter = lineFormatter.preformatted()
            bodyFormatter.beginLine()
            bodyFormatter.appendChunk(
                buildExpression(
                    expr,
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inString = true
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
            val bodyFormatter = lineFormatter.preformatted()
            bodyFormatter.beginLine()
            bodyFormatter.appendChunk(
                buildExpression(
                    expr,
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inString = true
                )
            )
            if (numParts > 2) {
              command.parts.slice(1, command.parts.size - 1).foreach { chunk =>
                bodyFormatter.appendChunk(
                    buildExpression(chunk,
                                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                                    inString = true)
                )
              }
            }
            bodyFormatter.appendChunk(
                buildExpression(
                    command.parts.last match {
                      case ValueString(s, text) =>
                        ValueString(commandEndRegexp.replaceFirstIn(s, ""), text)
                      case other => other
                    },
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inString = true
                )
            )
            bodyFormatter.endLine()
          }
        }

        lineFormatter.beginLine()
        lineFormatter.appendChunk(Token.fromStop(Symbols.CommandClose, command.text))
      }

      override def textSource: TextSource = command.text
    }

    case class RuntimeMetadataSection(runtimeKV: Vector[RuntimeKV]) extends StatementGroup {
      override val statements: Vector[Statement] = {
        runtimeKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    case class RuntimeBlock(runtime: RuntimeSection)
        extends BlockStatement(Symbols.Runtime, runtime.text) {
      override val body: Option[Chunk] = Some(RuntimeMetadataSection(runtime.kvs))
    }

    case class TaskSections(task: Task) extends Sections() {
      if (task.input.isDefined) {
        statements.append(InputsBlock(task.input.get))
      }

      if (task.declarations.nonEmpty) {
        statements.append(DeclarationsSection(task.declarations))
      }

      statements.append(CommandBlock(task.command))

      if (task.output.isDefined) {
        statements.append(OutputsBlock(task.output.get))
      }

      if (task.runtime.isDefined) {
        statements.append(RuntimeBlock(task.runtime.get))
      }

      if (task.meta.isDefined) {
        statements.append(MetaBlock(task.meta.get))
      }

      if (task.parameterMeta.isDefined) {
        statements.append(ParameterMetaBlock(task.parameterMeta.get))
      }
    }

    case class TaskBlock(task: Task) extends BlockStatement(Symbols.Task, task.text) {
      override val clause: Option[Atom] =
        Some(Token.fromPrev(task.name, keywordToken.textSource, spacing = 1))

      override val body: Option[Chunk] = Some(TaskSections(task))
    }

    case class DocumentSections() extends Sections {
      override val topCommentsAllowed: Boolean = false

      statements.append(VersionStatement(document.version))

      private val imports = document.elements.collect { case imp: ImportDoc => imp }
      if (imports.nonEmpty) {
        statements.append(ImportsSection(imports))
      }

      document.elements.foreach {
        case struct: TypeStruct => statements.append(StructBlock(struct))
      }

      if (document.workflow.isDefined) {
        statements.append(WorkflowBlock(document.workflow.get))
      }

      document.elements.foreach {
        case task: Task => statements.append(TaskBlock(task))
      }
    }

    def apply(): Vector[String] = {
      val documentSections = DocumentSections()

      if (opts.verbosity == Verbosity.Verbose) {
        println(Util.prettyFormat(documentSections))
      }

      val inlineComments = commentLines.toMap
        .map {
          case (line, text) => text -> getCommentAt(line)
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

  def formatDocument(doc: Document): Vector[String] = {
    FormatterDocument(doc).apply()
  }

  def formatDocuments(url: URL): Unit = {
    Parsers(opts).getDocumentWalker[Vector[String]](url, documents).walk { (url, doc, results) =>
      results(url) = formatDocument(doc)
    }
  }
}
