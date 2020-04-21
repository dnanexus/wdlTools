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
    def getTextSourceFromChunks(chunks: Vector[Chunk]): TextSource = {
      TextSource.fromSpan(chunks.head.textSource, chunks.last.textSource)
    }

    def getComments(textSource: TextSource,
                    startInclusive: Boolean = true,
                    stopInclusive: Boolean = false): Map[Int, Comment] = {
      val start = textSource.line + (if (startInclusive) 0 else 1)
      val stop = textSource.endLine - (if (stopInclusive) 0 else 1)
      document.comments.filterKeys(i => i >= start && i <= stop)
    }

    abstract class Atom extends Chunk {
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

      override def toString: String = value
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
          TextSource(line, col, line, col + value.length, before.url)
        } else if (preferBefore) {
          // assume the value is separated by the previous token by one space
          TextSource(before.endLine,
                     before.endCol + spacing,
                     before.endLine,
                     before.endCol + value.length + spacing,
                     before.url)
        } else {
          TextSource(after.line,
                     after.col - spacing,
                     after.col,
                     after.col - value.length - spacing,
                     after.url)
        }
        Token(value, textSource)
      }

      def fromPrev(value: String, prev: TextSource, spacing: Int = 0): Token = {
        val textSource = TextSource(
            prev.endLine,
            prev.endCol + spacing,
            prev.endLine,
            prev.endCol + value.length + spacing,
            prev.url
        )
        Token(value, textSource)
      }

      def fromNext(value: String, next: TextSource): Token = {
        val textSource = TextSource(
            next.line,
            next.col - value.length,
            next.line,
            next.col,
            next.url
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
      override def toString: String = s"${'"'}${value}${'"'}"

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
            prev.endCol + value.length + spacing,
            prev.url
        )
        StringLiteral(value, textSource)
      }
    }

    abstract class AtomSequence(atoms: Vector[Atom]) extends Atom {
      override def textSource: TextSource = getTextSourceFromChunks(atoms)
    }

    /**
      * A sequence of adjacent atoms (with no spacing or wrapping)
      *
      * @param atoms the atoms
      */
    case class Adjacent(atoms: Vector[Atom]) extends AtomSequence(atoms) {
      override def toString: String = {
        atoms.mkString("")
      }

      override def length: Int = {
        atoms.map(_.length).sum
      }
    }

    abstract class Group(prefix: Option[Atom] = None,
                         suffix: Option[Atom] = None,
                         override val wrapAll: Boolean = false)
        extends Atom {
      lazy val prefixLength: Int = prefix.map(_.length).getOrElse(0)
      lazy val suffixLength: Int = suffix.map(_.length).getOrElse(0)

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

      lazy val itemStr: String = items.mkString(separator)
      lazy val itemLength: Int = itemStr.length

      override def toString: String = {
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
            var prevItem = items.head
            lineFormatter.appendAll(Vector(prevItem))
            items.tail.foreach { item =>
              val delimiterToken = Token.fromPrev(separator, prevItem.textSource)
              lineFormatter.appendChunk(delimiterToken, spacing = "")
              if (wrapAll || item.length > lineFormatter.lengthRemaining) {
                lineFormatter.endLine(wrap = true, indenting = Indenting.Never)
                lineFormatter.beginLine()
              }
              lineFormatter.appendAll(Vector(item))
              prevItem = item
            }
            lineFormatter.endLine()
          } else if (wrapping == Wrapping.Never) {
            lineFormatter.appendString(itemStr)
          } else {
            lineFormatter.appendAll(items, wrapping = wrapping)
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
      lazy val delimiterToken: Token = Token.fromPrev(delimiter, key.textSource)

      override def toString: String = {
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
      override def toString: String = s"${oper}${value}"

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

      override def toString: String = {
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
        val operToken = Token.fromBetween(oper, lhs.textSource, rhs.textSource)
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

      override def toString: String = {
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

    def buildExpression(expr: Expr,
                        placeholderOpen: String = Symbols.PlaceholderOpenDollar,
                        inString: Boolean = false,
                        inPlaceholder: Boolean = false,
                        inOperation: Boolean = false): Atom = {

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
    abstract class Statement extends Chunk with Ordered[Statement] {
      override def compare(that: Statement): Int = {
        textSource.line - that.textSource.line
      }

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

      override lazy val textSource: TextSource = getTextSourceFromChunks(statements)
    }

    abstract class SectionsStatement extends Statement {
      def sections: Vector[Statement]

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        val comments: Map[Int, Comment] = getComments(textSource)

        if (sections.nonEmpty) {
          // Find all internal line comments - a line comment sticks with the closest statement
          // (before or after) and defaults to the next statement if it is equidistant to both.
          // To do this, we first need to sort the sections by their original order.
          val sortedSections = sections.sortWith(_ < _)

          sections.head.format(lineFormatter)
          sections.tail.foreach { section =>
            lineFormatter.emptyLine()
            section.format(lineFormatter)
          }
        } else if (comments.nonEmpty) {}
      }

      override def textSource: TextSource = getTextSourceFromChunks(sections.sortWith(_ < _))
    }

    class Sections extends SectionsStatement {
      val statements: mutable.Buffer[Statement] = mutable.ArrayBuffer.empty

      lazy override val sections: Vector[Statement] = statements.toVector
    }

    case class CommentSection(comments: Vector[Comment]) extends Statement {
      override lazy val textSource: TextSource =
        TextSource.fromSpan(comments.head.text, comments.last.text)

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.appendComments(comments)
      }
    }

    case class VersionStatement(version: Version) extends Statement {
      override val textSource: TextSource = version.text

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.beginLine()
        lineFormatter.appendAll(
            Vector(Token.fromStart(Symbols.Version, version.text),
                   Token.fromStop(version.value.name, version.text)),
            Wrapping.Never
        )
        lineFormatter.endLine()
      }
    }

    case class ImportStatement(importDoc: ImportDoc) extends Statement {
      override val textSource: TextSource = importDoc.text

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        //      if (importDoc.comment.isDefined) {
        //        lineFormatter.appendComment(importDoc.comment.get)
        //      }
        val keywordToken = Token.fromStart(Symbols.Import, importDoc.text)
        // assuming URL comes directly after keyword
        val urlLiteral = StringLiteral.fromPrev(importDoc.url.toString, keywordToken.textSource)

        lineFormatter.beginLine()
        lineFormatter.appendAll(Vector(keywordToken, urlLiteral), Wrapping.Never)
        if (importDoc.name.isDefined) {
          // assuming namespace comes directly after url
          lineFormatter.appendAll(Token.chain(Vector(Symbols.As, importDoc.name.get.value),
                                              urlLiteral.textSource,
                                              spacing = 1),
                                  Wrapping.AsNeeded)
        }
        importDoc.aliases.foreach { alias =>
          lineFormatter.appendAll(
              Token.chain(Vector(Symbols.Alias, alias.id1, Symbols.As, alias.id2),
                          alias.text,
                          spacing = 1),
              Wrapping.Always
          )
        }
        lineFormatter.endLine()
      }
    }

    case class ImportsSection(imports: Vector[ImportDoc]) extends StatementGroup {
      override def statements: Vector[Statement] = {
        imports.map(ImportStatement)
      }
    }

    abstract class DeclarationBase(name: String,
                                   wdlType: Type,
                                   expr: Option[Expr] = None,
                                   override val textSource: TextSource)
        extends Statement {

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        //      if (comment.isDefined) {
        //        lineFormatter.appendComment(comment.get)
        //      }
        val typeAtom = DataType.fromWdlType(wdlType)
        val nameToken = Token.fromPrev(name, typeAtom.textSource, spacing = 1)
        lineFormatter.beginLine()
        lineFormatter.appendAll(Vector(typeAtom, nameToken))
        if (expr.isDefined) {
          val eqToken = Token.fromPrev(Symbols.Assignment, nameToken.textSource, spacing = 1)
          lineFormatter.appendAll(Vector(eqToken, buildExpression(expr.get)))
        }
        lineFormatter.endLine()
      }
    }

    case class DeclarationStatement(decl: Declaration)
        extends DeclarationBase(decl.name, decl.wdlType, decl.expr, decl.text)

    case class StructMemberStatement(member: StructMember)
        extends DeclarationBase(member.name, member.dataType, textSource = member.text)

    case class MembersSection(members: Vector[StructMember]) extends StatementGroup {
      override def statements: Vector[Statement] = {
        members.map(StructMemberStatement)
      }
    }

    case class DeclarationsSection(declarations: Vector[Declaration]) extends StatementGroup {
      override def statements: Vector[Statement] = {
        declarations.map(DeclarationStatement)
      }
    }

    case class KVStatement(id: String, expr: Expr, override val textSource: TextSource)
        extends Statement {
      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        val idToken = Token.fromStart(id, textSource)
        val delimToken = Token.fromPrev(Symbols.KeyValueDelimiter, idToken.textSource)
        lineFormatter.beginLine()
        lineFormatter.appendAll(
            Vector(Adjacent(Vector(idToken, delimToken)), buildExpression(expr))
        )
        lineFormatter.endLine()
      }
    }

    case class MetadataSection(metaKV: Vector[MetaKV]) extends StatementGroup {
      override def statements: Vector[Statement] = {
        metaKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    sealed abstract class BlockStatement(keyword: String, override val textSource: TextSource)
        extends Statement {

      def keywordToken: Token = Token.fromStart(keyword, textSource)

      def clause: Option[Atom] = None

      def body: Option[Chunk] = None

      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        // assume the open brace is on the same line as the keyword/clause
        val openToken =
          Token.fromPrev(Symbols.BlockOpen, clause.getOrElse(keywordToken).textSource, spacing = 1)
        lineFormatter.beginLine()
        lineFormatter.appendAll(Vector(Some(keywordToken), clause, Some(openToken)).flatten)
        if (body.isDefined) {
          lineFormatter.endLine()
          body.get.format(lineFormatter.indented())
          lineFormatter.beginLine()
        }
        lineFormatter.appendChunk(Token.fromStop(Symbols.BlockClose, textSource))
        lineFormatter.endLine()
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
      override def sections: Vector[Statement] = {
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
      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        val args = inputs.value.map { inp =>
          val nameToken = Token.fromStart(inp.name, inp.text)
          SpacedContainer(
              Vector(nameToken,
                     Token.fromBetween(Symbols.Assignment, nameToken.textSource, inp.expr.text),
                     buildExpression(inp.expr)),
              textSource = inp.text
          )
        }
        lineFormatter.beginLine()
        lineFormatter.appendAll(
            Vector(Adjacent(
                       Token.chain(Vector(Symbols.Input, Symbols.KeyValueDelimiter), inputs.text)
                   ),
                   DelimitedContainer(args, textSource = getTextSourceFromChunks(args)))
        )
        lineFormatter.endLine()
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

      override def body: Option[Chunk] = Some(WorkflowElementBody(scatter.body))
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

      override def body: Option[Chunk] = Some(WorkflowElementBody(conditional.body))
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

      override def clause: Option[Atom] =
        Some(Token.fromPrev(workflow.name, keywordToken.textSource, spacing = 1))

      override def body: Option[Chunk] = Some(WorkflowSections(workflow))
    }

    private val commandStartRegexp = "^[\n\r]+".r
    private val commandEndRegexp = "\\s+$".r
    private val commandSingletonRegexp = "^[\n\r]*(.*?)\\s+$".r

    case class CommandBlock(command: CommandSection) extends Statement {
      override def formatChunks(lineFormatter: LineFormatter): Unit = {
        lineFormatter.beginLine()
        lineFormatter.appendAll(
            Token.chain(Vector(Symbols.Command, Symbols.CommandOpen), command.text, spacing = 1)
        )
        lineFormatter.endLine()

        val numParts = command.parts.size
        if (numParts > 0) {
          val bodyFormatter = lineFormatter.preformatted()
          bodyFormatter.beginLine()
          if (numParts == 1) {
            bodyFormatter.appendChunk(
                buildExpression(
                    command.parts.head match {
                      case s: ValueString =>
                        s.value match {
                          case commandSingletonRegexp(body, _) => ValueString(body, s.text)
                          case _                               => s
                        }
                      case other => other
                    },
                    placeholderOpen = Symbols.PlaceholderOpenTilde,
                    inString = true
                )
            )
          } else if (numParts > 1) {
            bodyFormatter.appendChunk(
                buildExpression(
                    command.parts.head match {
                      case ValueString(s, text) =>
                        ValueString(commandStartRegexp.replaceFirstIn(s, ""), text)
                      case other => other
                    },
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
          }
          bodyFormatter.endLine()
        }

        lineFormatter.beginLine()
        lineFormatter.appendChunk(Token.fromStop(Symbols.CommandClose, command.text))
        lineFormatter.endLine()
      }

      override def textSource: TextSource = command.text
    }

    case class RuntimeMetadataSection(runtimeKV: Vector[RuntimeKV]) extends StatementGroup {
      override def statements: Vector[Statement] = {
        runtimeKV.map(kv => KVStatement(kv.id, kv.expr, kv.text))
      }
    }

    case class RuntimeBlock(runtime: RuntimeSection)
        extends BlockStatement(Symbols.Runtime, runtime.text) {
      override def body: Option[Chunk] = Some(RuntimeMetadataSection(runtime.kvs))
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
      override def clause: Option[Atom] =
        Some(Token.fromPrev(task.name, keywordToken.textSource, spacing = 1))

      override def body: Option[Chunk] = Some(TaskSections(task))
    }

    case class DocumentSections() extends Sections {
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

    def format(): Vector[String] = {
      val documentSections = DocumentSections()

      if (opts.verbosity == Verbosity.Verbose) {
        println(Util.prettyFormat(documentSections))
      }

      val lineFormatter = DefaultLineFormatter()

      documentSections.format(lineFormatter)

      lineFormatter.toVector
    }
  }

  def formatDocument(doc: Document): Vector[String] = {
    FormatterDocument(doc).format()
  }

  def formatDocuments(url: URL): Unit = {
    Parsers(opts).getDocumentWalker[Vector[String]](url, documents).walk { (url, doc, results) =>
      results(url) = formatDocument(doc)
    }
  }
}

// This stuff may be useful when re-writing
//case class DefaultAtomizer(defaultSpacing: Int = 1) extends Atomizer {
//  object Wrapping {
//    val Undefined: Int = -1
//    val Never: Int = 0 // never place a newline after the atom
//    val VeryLow: Int = 1
//    val Low: Int = 3
//    val Medium: Int = 5
//    val High: Int = 7
//    val VeryHigh: Int = 9
//    val Always: Int = 10 // always place a newline after the atom
//  }
//
//  case class FormatterToken(override val token: Token,
//                            override val spaceBefore: Int = Spacing.Undefined,
//                            override val spaceAfter: Int = Spacing.Undefined,
//                            override val wrapBefore: Int = Wrapping.Undefined,
//                            override val wrapAfter: Int = Wrapping.Undefined)
//      extends TokenAtom(token,
//                        spaceBefore,
//                        spaceAfter,
//                        wrapBefore = wrapBefore,
//                        wrapAfter = wrapAfter)
//
//  case class Keyword(override val token: Token) extends FormatterToken(token)
//
//  case class Operator(override val token: Token, override val wrapBefore: Int = Wrapping.Medium)
//      extends FormatterToken(token, defaultSpacing, defaultSpacing, wrapBefore, Wrapping.Low)
//
//  case class Unary(override val token: Token)
//      extends FormatterToken(token,
//                             defaultSpacing,
//                             Spacing.None,
//                             Wrapping.Undefined,
//                             Wrapping.Never)
//
//  case class Delimiter(override val token: Token)
//      extends FormatterToken(token, Spacing.None, defaultSpacing, Wrapping.Never, Wrapping.VeryHigh)
//
//  case class Quantifier(override val token: Token)
//      extends FormatterToken(token, Spacing.None, defaultSpacing, Wrapping.Never, Wrapping.VeryLow)
//
//  case class Option(override val token: Token)
//      extends FormatterToken(token,
//                             Spacing.None,
//                             Spacing.None,
//                             Wrapping.Undefined,
//                             Wrapping.VeryLow)
//
//  case class BlockOpen(override val token: Token)
//      extends FormatterToken(token, defaultSpacing, Spacing.None, Wrapping.VeryLow, Wrapping.Always)
//
//  case class BlockClose(override val token: Token)
//      extends FormatterToken(token, Spacing.None, Spacing.None, Wrapping.Undefined, Wrapping.Always)
//
//  case class GroupOpen(override val token: Token)
//      extends FormatterToken(token,
//                             Spacing.Undefined,
//                             Spacing.None,
//                             Wrapping.Undefined,
//                             Wrapping.VeryHigh)
//
//  case class GroupClose(override val token: Token)
//      extends FormatterToken(token,
//                             Spacing.None,
//                             Spacing.Undefined,
//                             Wrapping.Undefined,
//                             Wrapping.VeryHigh)
//
//  case class PlaceholderOpen(override val token: Token)
//      extends FormatterToken(token,
//                             Spacing.Undefined,
//                             Spacing.None,
//                             Wrapping.Undefined,
//                             Wrapping.Low)
//
//  private val defaults: Map[Token, Atom] = Vector(
//      FormatterToken(Tokens.Access, Spacing.None, Spacing.None, Wrapping.Low, Wrapping.Never),
//      Operator(Tokens.Addition),
//      Delimiter(Tokens.ArrayDelimiter),
//      GroupOpen(Tokens.ArrayLiteralOpen),
//      GroupClose(Tokens.ArrayLiteralClose),
//      Operator(Tokens.Assignment),
//      BlockOpen(Tokens.BlockOpen),
//      BlockClose(Tokens.BlockClose),
//      GroupOpen(Tokens.ClauseOpen),
//      GroupClose(Tokens.ClauseClose),
//      BlockOpen(Tokens.CommandOpen),
//      BlockClose(Tokens.CommandClose),
//      Option(Tokens.DefaultOption),
//      Operator(Tokens.Division),
//      Operator(Tokens.Equality),
//      Option(Tokens.FalseOption),
//      GroupOpen(Tokens.FunctionCallOpen),
//      GroupClose(Tokens.FunctionCallClose),
//      Operator(Tokens.GreaterThan),
//      Operator(Tokens.GreaterThanOrEqual),
//      GroupOpen(Tokens.GroupOpen),
//      GroupClose(Tokens.GroupClose),
//      GroupOpen(Tokens.IndexOpen),
//      GroupClose(Tokens.IndexClose),
//      Operator(Tokens.Inequality),
//      Delimiter(Tokens.KeyValueDelimiter),
//      Operator(Tokens.LessThan),
//      Operator(Tokens.LessThanOrEqual),
//      Operator(Tokens.LogicalAnd),
//      Operator(Tokens.LogicalOr),
//      Unary(Tokens.LogicalNot),
//      GroupOpen(Tokens.MapOpen),
//      GroupClose(Tokens.MapClose),
//      Delimiter(Tokens.MemberDelimiter),
//      Operator(Tokens.Multiplication),
//      Quantifier(Tokens.NonEmpty),
//      GroupOpen(Tokens.ObjectOpen),
//      GroupClose(Tokens.ObjectClose),
//      Quantifier(Tokens.Optional),
//      PlaceholderOpen(Tokens.PlaceholderOpenTilde),
//      PlaceholderOpen(Tokens.PlaceholderOpenDollar),
//      FormatterToken(Tokens.PlaceholderClose,
//                     Spacing.None,
//                     Spacing.Undefined,
//                     Wrapping.VeryLow,
//                     Wrapping.Undefined),
//      FormatterToken(Tokens.QuoteOpen,
//                     Spacing.Undefined,
//                     Spacing.None,
//                     Wrapping.Undefined,
//                     Wrapping.Never),
//      FormatterToken(Tokens.QuoteClose,
//                     Spacing.None,
//                     Spacing.Undefined,
//                     Wrapping.Never,
//                     Wrapping.Undefined),
//      Operator(Tokens.Remainder),
//      Option(Tokens.SepOption),
//      FormatterToken(Tokens.StatementEnd,
//                     Spacing.Undefined,
//                     Spacing.None,
//                     Wrapping.Never,
//                     Wrapping.Always),
//      Operator(Tokens.Subtraction),
//      Option(Tokens.TrueOption),
//      GroupOpen(Tokens.TypeParamOpen),
//      GroupClose(Tokens.TypeParamClose),
//      Unary(Tokens.UnaryMinus),
//      Unary(Tokens.UnaryPlus)
//  ).map { tok: FormatterToken =>
//    tok.token -> tok
//  }.toMap
//}
