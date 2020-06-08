package wdlTools.generators.code

import wdlTools.generators.code.BaseWdlGenerator._
import wdlTools.generators.code.Indenting.Indenting
import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes.{T_Int, T_Object, T_String, _}
import wdlTools.syntax.WdlVersion

import scala.collection.mutable

case class WdlV1Generator() {

  private case class Literal(value: Any, quoting: Boolean = false) extends Sized {
    override lazy val length: Int = toString.length

    override lazy val toString: String = {
      if (quoting) {
        s"${'"'}${value}${'"'}"
      } else {
        value.toString
      }
    }
  }

  private case class SizedSequence(sizeds: Vector[Sized],
                                   wrapping: Wrapping = Wrapping.Never,
                                   spacing: Spacing = Spacing.Off)
      extends Composite {
    require(sizeds.nonEmpty)

    override lazy val length: Int = sizeds.map(_.length).sum + (
        if (spacing == Spacing.On) sizeds.length else 0
    )

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping).appendAll(sizeds)
    }
  }

  private abstract class Group(ends: Option[(Sized, Sized)] = None,
                               val wrapping: Wrapping = Wrapping.Never,
                               val spacing: Spacing = Spacing.On)
      extends Composite {

    private val endLengths: (Int, Int) =
      ends.map(e => (e._1.length, e._2.length)).getOrElse((0, 0))

    override lazy val length: Int = body.map(_.length).getOrElse(0) + endLengths._1 + endLengths._2

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      if (ends.isDefined) {
        val (prefix, suffix) = ends.get
        val wrapAndIndentEnds = wrapping != Wrapping.Never && endLengths._1 > lineGenerator.lengthRemaining
        if (wrapAndIndentEnds) {
          lineGenerator.endLine(continue = true)
          lineGenerator.beginLine()
        }
        if (body.nonEmpty && (wrapping == Wrapping.Always || length > lineGenerator.lengthRemaining)) {
          lineGenerator.append(prefix)

          val bodyFormatter = lineGenerator
            .derive(increaseIndent = wrapAndIndentEnds,
                    newSpacing = Spacing.On,
                    newWrapping = wrapping)
          bodyFormatter.endLine(continue = true)
          bodyFormatter.beginLine()
          bodyFormatter.append(body.get)

          lineGenerator.endLine(continue = wrapAndIndentEnds)
          lineGenerator.beginLine()
          lineGenerator.append(suffix)
        } else {
          val adjacentFormatter = lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping)
          adjacentFormatter.appendPrefix(prefix)
          if (body.nonEmpty) {
            adjacentFormatter.append(body.get)
          }
          adjacentFormatter.appendSuffix(suffix)
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
                               override val wrapping: Wrapping = Wrapping.AsNeeded)
      extends Group(ends = ends, wrapping = wrapping) {

    override lazy val body: Option[Composite] = if (items.nonEmpty) {
      Some(
          SizedSequence(
              items.zipWithIndex.map {
                case (item, i) if i < items.size - 1 =>
                  if (delimiter.isDefined) {
                    val delimiterLiteral = Literal(delimiter.get)
                    SizedSequence(Vector(item, delimiterLiteral))
                  } else {
                    item
                  }
                case (item, _) => item
              },
              wrapping = wrapping,
              spacing = Spacing.On
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

    override def length: Int = key.length + delimiterLiteral.length + value.length + 1

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(Vector(SizedSequence(Vector(key, delimiterLiteral)), value))
    }
  }

  private object DataType {
    def buildDataType(name: String,
                      inner1: Option[Sized] = None,
                      inner2: Option[Sized] = None,
                      quantifier: Option[String] = None): Sized = {
      val nameLiteral: Literal = Literal(name)
      val quantifierLiteral: Option[Literal] =
        quantifier.map(sym => Literal(sym))
      if (inner1.isDefined) {
        // making the assumption that the open token comes directly after the name
        val openLiteral = Literal(Symbols.TypeParamOpen)
        val prefix = SizedSequence(Vector(nameLiteral, openLiteral))
        // making the assumption that the close token comes directly before the quantifier (if any)
        val closeLiteral = if (quantifierLiteral.isDefined) {
          Literal(Symbols.TypeParamClose)
        } else {
          Literal(Symbols.TypeParamClose)
        }
        val suffix = SizedSequence(Vector(Some(closeLiteral), quantifierLiteral).flatten)
        Container(
            Vector(inner1, inner2).flatten,
            Some(Symbols.ArrayDelimiter),
            Some((prefix, suffix))
        )
      } else if (quantifier.isDefined) {
        SizedSequence(Vector(nameLiteral, quantifierLiteral.get))
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

    def fromWdlType(wdlType: T, quantifier: Option[Literal] = None): Sized = {
      wdlType match {
        case T_Optional(inner) =>
          fromWdlType(inner, quantifier = Some(Literal(Symbols.Optional)))
        case T_Array(inner, nonEmpty) =>
          val quant = if (nonEmpty) {
            Some(Symbols.NonEmpty)
          } else {
            None
          }
          buildDataType(Symbols.ArrayType, Some(fromWdlType(inner)), quantifier = quant)
        case T_Map(keyType, valueType) if isPrimitiveType(keyType) =>
          buildDataType(Symbols.MapType, Some(fromWdlType(keyType)), Some(fromWdlType(valueType)))
        case T_Pair(left, right) =>
          buildDataType(Symbols.PairType, Some(fromWdlType(left)), Some(fromWdlType(right)))
        case T_Struct(name, _) => Literal(name)
        case T_Object          => Literal(Symbols.ObjectType)
        case T_String          => Literal(Symbols.StringType)
        case T_Boolean         => Literal(Symbols.BooleanType)
        case T_Int             => Literal(Symbols.IntType)
        case T_Float           => Literal(Symbols.FloatType)
        case other             => throw new Exception(s"Unrecognized type $other")
      }
    }
  }

  private case class Operation(oper: String,
                               lhs: Sized,
                               rhs: Sized,
                               grouped: Boolean = false,
                               inString: Boolean)
      extends Group(ends = if (grouped) {
        Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose))
      } else {
        None
      }, wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded)
      with Composite {

    override lazy val body: Option[Composite] = {
      val operLiteral = Literal(oper)
      Some(SizedSequence(Vector(lhs, operLiteral, rhs), wrapping = wrapping, spacing = Spacing.On))
    }
  }

  private case class Placeholder(value: Sized,
                                 open: String = Symbols.PlaceholderOpenDollar,
                                 close: String = Symbols.PlaceholderClose,
                                 options: Option[Vector[Sized]] = None,
                                 inString: Boolean)
      extends Group(
          ends = Some(Literal(open), Literal(close)),
          wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded,
          spacing = if (inString) Spacing.Off else Spacing.On
      )
      with Composite {

    override lazy val body: Option[Composite] = Some(
        SizedSequence(
            options.getOrElse(Vector.empty) ++ Vector(value),
            wrapping = wrapping,
            spacing = Spacing.On
        )
    )
  }

  private case class CompoundString(sizeds: Vector[Sized], quoting: Boolean) extends Composite {
    override lazy val length: Int = sizeds
      .map(_.length)
      .sum + (if (quoting) 2 else 0)

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      val unspacedFormatter =
        lineGenerator.derive(newWrapping = Wrapping.Never, newSpacing = Spacing.Off)
      if (quoting) {
        unspacedFormatter.appendPrefix(
            Literal(Symbols.QuoteOpen)
        )
        unspacedFormatter.appendAll(sizeds)
        unspacedFormatter.appendSuffix(
            Literal(Symbols.QuoteClose)
        )
      } else {
        unspacedFormatter.appendAll(sizeds)
      }
    }
  }

  private def buildExpression(
      expr: Expr,
      placeholderOpen: String = Symbols.PlaceholderOpenDollar,
      inStringOrCommand: Boolean = false,
      inPlaceholder: Boolean = false,
      inOperation: Boolean = false,
      parentOperation: Option[String] = None,
      stringModifier: Option[String => String] = None
  ): Sized = {
    // Builds an expression that occurs nested within another expression. By default, passes
    //all the current parameter values to the nested call.
    // @param nestedExpression the nested Expr
    // @param placeholderOpen  override the current value of `placeholderOpen`
    // @param inString         override the current value of `inString`
    // @param inPlaceholder    override the current value of `inPlaceholder`
    // @param inOperation      override the current value of `inOperation`
    // @param parentOperation  if `inOperation` is true, this is the parent operation - nested
    //                         same operations are not grouped.
    // @return a Sized
    def nested(nestedExpression: Expr,
               placeholderOpen: String = placeholderOpen,
               inString: Boolean = inStringOrCommand,
               inPlaceholder: Boolean = inPlaceholder,
               inOperation: Boolean = inOperation,
               parentOperation: Option[String] = None): Sized = {
      buildExpression(
          nestedExpression,
          placeholderOpen = placeholderOpen,
          inStringOrCommand = inString,
          inPlaceholder = inPlaceholder,
          inOperation = inOperation,
          parentOperation = parentOperation,
          stringModifier = stringModifier
      )
    }

    def unary(oper: String, value: Expr): Sized = {
      val operSized = Literal(oper)
      SizedSequence(Vector(operSized, nested(value, inOperation = true)))
    }

    def operation(oper: String, lhs: Expr, rhs: Expr): Sized = {
      Operation(
          oper,
          nested(lhs,
                 inPlaceholder = inStringOrCommand,
                 inOperation = true,
                 parentOperation = Some(oper)),
          nested(rhs,
                 inPlaceholder = inStringOrCommand,
                 inOperation = true,
                 parentOperation = Some(oper)),
          grouped = inOperation && !parentOperation.contains(oper),
          inString = inStringOrCommand
      )
    }

    def option(name: String, value: Expr): Sized = {
      val exprSized = nested(value, inPlaceholder = true)
      val eqLiteral = Literal(Symbols.Assignment)
      val nameLiteral = Literal(name)
      SizedSequence(Vector(nameLiteral, eqLiteral, exprSized))
    }

    expr match {
      // literal values
      case ValueNone(_, _) => Literal(Symbols.None)
      case ValueString(value, _, _) =>
        val v = if (stringModifier.isDefined) {
          stringModifier.get(value)
        } else {
          value
        }
        Literal(v, quoting = inPlaceholder || !inStringOrCommand)
      case ValueBoolean(value, _, _) => Literal(value)
      case ValueInt(value, _, _)     => Literal(value)
      case ValueFloat(value, _, _)   => Literal(value)
      case ExprPair(left, right, _, _) if !(inStringOrCommand || inPlaceholder) =>
        Container(
            Vector(nested(left), nested(right)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose))
        )
      case ExprArray(value, _, _) =>
        Container(
            value.map(nested(_)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose))
        )
      case ExprMap(value, _, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(nested(k), nested(v))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.MapOpen), Literal(Symbols.MapClose)),
            Wrapping.Always
        )
      case ExprObject(value, _, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(Literal(k), nested(v))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ObjectOpen), Literal(Symbols.ObjectClose)),
            Wrapping.Always
        )
      // placeholders
      case ExprPlaceholderEqual(t, f, value, _, _) =>
        Placeholder(
            nested(value, inPlaceholder = true),
            placeholderOpen,
            options = Some(
                Vector(
                    option(Symbols.TrueOption, t),
                    option(Symbols.FalseOption, f)
                )
            ),
            inString = inStringOrCommand
        )
      case ExprPlaceholderDefault(default, value, _, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Symbols.DefaultOption, default))),
                    inString = inStringOrCommand)
      case ExprPlaceholderSep(sep, value, _, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Symbols.SepOption, sep))),
                    inString = inStringOrCommand)
      case ExprCompoundString(value, _, _) if !inPlaceholder =>
        // Often/always an ExprCompoundString contains one or more empty
        // ValueStrings that we want to get rid of because they're useless
        // and can mess up formatting
        val filteredExprs = value.filter {
          case ValueString(s, _, _) => s.nonEmpty
          case _                    => true
        }
        CompoundString(filteredExprs.map(nested(_, inString = true)), quoting = !inStringOrCommand)
      // other expressions need to be wrapped in a placeholder if they
      // appear in a string or command block
      case other =>
        val sized = other match {
          case ExprUnaryPlus(value, _, _)  => unary(Symbols.UnaryPlus, value)
          case ExprUnaryMinus(value, _, _) => unary(Symbols.UnaryMinus, value)
          case ExprNegate(value, _, _)     => unary(Symbols.LogicalNot, value)
          case ExprLor(a, b, _, _)         => operation(Symbols.LogicalOr, a, b)
          case ExprLand(a, b, _, _)        => operation(Symbols.LogicalAnd, a, b)
          case ExprEqeq(a, b, _, _)        => operation(Symbols.Equality, a, b)
          case ExprLt(a, b, _, _)          => operation(Symbols.LessThan, a, b)
          case ExprLte(a, b, _, _)         => operation(Symbols.LessThanOrEqual, a, b)
          case ExprGt(a, b, _, _)          => operation(Symbols.GreaterThan, a, b)
          case ExprGte(a, b, _, _)         => operation(Symbols.GreaterThanOrEqual, a, b)
          case ExprNeq(a, b, _, _)         => operation(Symbols.Inequality, a, b)
          case ExprAdd(a, b, _, _)         => operation(Symbols.Addition, a, b)
          case ExprSub(a, b, _, _)         => operation(Symbols.Subtraction, a, b)
          case ExprMul(a, b, _, _)         => operation(Symbols.Multiplication, a, b)
          case ExprDivide(a, b, _, _)      => operation(Symbols.Division, a, b)
          case ExprMod(a, b, _, _)         => operation(Symbols.Remainder, a, b)
          case ExprIdentifier(id, _, _)    => Literal(id)
          case ExprAt(array, index, _, _) =>
            val arraySized = nested(array, inPlaceholder = inStringOrCommand)
            val prefix = SizedSequence(
                Vector(arraySized, Literal(Symbols.IndexOpen))
            )
            val suffix = Literal(Symbols.IndexClose)
            Container(
                Vector(nested(index, inPlaceholder = inStringOrCommand)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix)
            )
          case ExprIfThenElse(cond, tBranch, fBranch, _, _) =>
            val condSized = nested(cond, inOperation = false, inPlaceholder = inStringOrCommand)
            val tSized = nested(tBranch, inOperation = false, inPlaceholder = inStringOrCommand)
            val fSized = nested(fBranch, inOperation = false, inPlaceholder = inStringOrCommand)
            Container(
                Vector(
                    Literal(Symbols.If),
                    condSized,
                    Literal(Symbols.Then),
                    tSized,
                    Literal(Symbols.Else),
                    fSized
                ),
                wrapping = Wrapping.AsNeeded
            )
          case ExprApply(funcName, _, elements, _, _) =>
            val prefix = SizedSequence(
                Vector(Literal(funcName), Literal(Symbols.FunctionCallOpen))
            )
            val suffix = Literal(Symbols.FunctionCallClose)
            Container(
                elements.map(nested(_, inPlaceholder = inStringOrCommand)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix)
            )
          case ExprGetName(e, id, _, _) =>
            val exprSized = nested(e, inPlaceholder = inStringOrCommand)
            val idLiteral = Literal(id)
            SizedSequence(
                Vector(exprSized, Literal(Symbols.Access), idLiteral)
            )
          case other => throw new Exception(s"Unrecognized expression $other")
        }
        if (inStringOrCommand && !inPlaceholder) {
          Placeholder(sized, placeholderOpen, inString = inStringOrCommand)
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
    private val versionToken = Literal(WdlVersion.V1.name)

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
    private val urlLiteral = Literal(importDoc.addr)
    private val nameTokens = Vector(Literal(Symbols.As), Literal(importDoc.namespace))
    private val aliasTokens = importDoc.aliases.map { alias =>
      Vector(Literal(Symbols.Alias), Literal(alias.id1), Literal(Symbols.As), Literal(alias.id2))
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, urlLiteral))
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
      statements.head.format(lineGenerator)
      statements.tail.foreach { section =>
        if (emtpyLineBetweenStatements) {
          lineGenerator.emptyLine()
        }
        section.format(lineGenerator)
      }
    }
  }

  private case class DeclarationStatement(name: String, wdlType: T, expr: Option[Expr] = None)
      extends BaseStatement {

    private val typeSized = DataType.fromWdlType(wdlType)
    private val nameLiteral = Literal(name)
    private val lhs = Vector(typeSized, nameLiteral)
    private val rhs = expr.map { e =>
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

  private abstract class BlockStatement(keyword: String) extends Statement {
    def clause: Option[Sized] = None

    def body: Option[Statement] = None

    protected val keywordLiteral: Literal = Literal(keyword)

    private val clauseSized: Option[Sized] = clause
    // assume the open brace is on the same line as the keyword/clause
    private val openLiteral =
      Literal(Symbols.BlockOpen)
    private val bodyStatement: Option[Statement] = body
    private val closeLiteral = Literal(Symbols.BlockClose)

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      lineGenerator.appendAll(Vector(Some(keywordLiteral), clauseSized, Some(openLiteral)).flatten)
      if (bodyStatement.isDefined) {
        lineGenerator.endLine()
        bodyStatement.get.format(lineGenerator.derive(increaseIndent = true))
        lineGenerator.beginLine()
      }
      lineGenerator.append(closeLiteral)
      lineGenerator.endLine()
    }
  }

  private case class InputsBlock(inputs: Vector[InputDefinition])
      extends BlockStatement(Symbols.Input) {
    override def body: Option[Statement] =
      Some(Section(inputs.map {
        case RequiredInputDefinition(name, wdlType, _) => DeclarationStatement(name, wdlType)
        case OverridableInputDefinitionWithDefault(name, wdlType, defaultExpr, _) =>
          DeclarationStatement(name, wdlType, Some(defaultExpr))
        case OptionalInputDefinition(name, wdlType, _) => DeclarationStatement(name, wdlType)
      }))
  }

  private def buildMeta(metaValue: MetaValue): Sized = {
    metaValue match {
      // literal values
      case MetaValueNull(_) => Literal(Symbols.Null)
      case MetaValueString(value, _) =>
        Literal(value, quoting = true)
      case MetaValueBoolean(value, _) => Literal(value)
      case MetaValueInt(value, _)     => Literal(value)
      case MetaValueFloat(value, _)   => Literal(value)
      case MetaValueArray(value, _) =>
        Container(
            value.map(buildMeta),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose))
        )
      case MetaValueObject(value, _) =>
        Container(
            value.map {
              case (name, value) => KeyValue(Literal(name), buildMeta(value))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ObjectOpen), Literal(Symbols.ObjectClose)),
            Wrapping.Always
        )
    }
  }

  private case class StructBlock(struct: StructDefinition) extends BlockStatement(Symbols.Struct) {
    override def clause: Option[Sized] = Some(
        Literal(struct.name)
    )

    override def body: Option[Statement] =
      Some(Section(struct.members.map {
        case (name, wdlType) => DeclarationStatement(name, wdlType)
      }.toVector))
  }

  private case class OutputsBlock(outputs: Vector[OutputDefinition])
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
      lineGenerator.appendAll(Vector(SizedSequence(lhs), rhs))
    }
  }

  private case class MetaBlock(kvs: Map[String, MetaValue]) extends BlockStatement(Symbols.Meta) {
    override def body: Option[Statement] =
      Some(Section(kvs.map {
        case (k, v) => MetaKVStatement(k, v)
      }.toVector))
  }

  private def splitWorkflowElements(elements: Vector[WorkflowElement]): Vector[Statement] = {
    var statements: Vector[Statement] = Vector.empty
    var declarations: Vector[Declaration] = Vector.empty

    elements.foreach {
      case declaration: Declaration => declarations :+= declaration
      case other =>
        if (declarations.nonEmpty) {
          statements :+= Section(declarations.map { decl =>
            DeclarationStatement(decl.name, decl.wdlType, decl.expr)
          })
          declarations = Vector.empty
        }
        statements :+= (other match {
          case call: Call               => CallBlock(call)
          case scatter: Scatter         => ScatterBlock(scatter)
          case conditional: Conditional => ConditionalBlock(conditional)
          case other                    => throw new Exception(s"Unexpected workflow body element $other")
        })
    }

    if (declarations.nonEmpty) {
      statements :+= Section(declarations.map { decl =>
        DeclarationStatement(decl.name, decl.wdlType, decl.expr)
      })
    }

    statements
  }

  private case class CallInputsStatement(inputs: Map[String, Expr]) extends BaseStatement {
    private val key = Literal(Symbols.Input)
    private val value = inputs.map {
      case (name, expr) =>
        val nameToken = Literal(name)
        val exprSized = buildExpression(expr)
        Container(
            Vector(nameToken, Literal(Symbols.Assignment), exprSized)
        )
    }.toVector

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      KeyValue(
          key,
          Container(value,
                    delimiter = Some(s"${Symbols.ArrayDelimiter}"),
                    wrapping = Wrapping.Always)
      )
    }
  }

  private case class CallBlock(call: Call) extends BlockStatement(Symbols.Call) {
    override def clause: Option[Sized] = Some(
        if (call.alias.isDefined) {
          val alias = call.alias.get
          // assuming all parts of the clause are adjacent
          val tokens =
            Vector(Literal(call.actualName), Literal(Symbols.As), Literal(alias))
          Container(tokens)
        } else {
          Literal(call.actualName)
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
      val exprAtom = buildExpression(scatter.expr)
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
      val exprAtom = buildExpression(conditional.expr)
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
            workflow.meta.map(meta => MetaBlock(meta.kvs)),
            workflow.parameterMeta.map(paramMeta => MetaBlock(paramMeta.kvs))
        ).flatten
      }
      Some(Section(statements, emtpyLineBetweenStatements = true))
    }
  }

  private case class CommandBlock(command: CommandSection) extends BaseStatement {
    // The command block is considered "preformatted" in that we don't try to reformat it.
    // However, we do need to try to indent it correclty. We do this by detecting the amount
    // of indent used on the first non-empty line and remove that from every line and replace
    // it by the lineGenerator's current indent level.
    private val commandStartRegexp = "^.*[\n\r]+([ \\t]*)(.*)".r
    private val commandEndRegexp = "\\s+$".r
    private val commandSingletonRegexp = "^.*[\n\r]*[ \\t]*(.*?)\\s*$".r

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(
          Vector(Literal(Symbols.Command), Literal(Symbols.CommandOpen))
      )
      if (command.parts.nonEmpty) {
        // The parser swallows anyting after the opening token ('{' or '<<<')
        // as part of the comment block, so we need to parse out any in-line
        // comment and append it separately
        val numParts = command.parts.size
        if (numParts == 1) {
          val expr = command.parts.head match {
            case s: ValueString =>
              s.value match {
                case commandSingletonRegexp(body) => ValueString(body, null, null)
                case _                            => s
              }
            case other => other
          }
          lineGenerator.endLine()

          val bodyFormatter =
            lineGenerator.derive(increaseIndent = true, newSpacing = Spacing.Off)
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
          val (expr, indent) = command.parts.head match {
            case s: ValueString =>
              s.value match {
                case commandStartRegexp(indent, body) =>
                  (ValueString(body, null, null), indent)
                case _ => (s, "")
              }
            case other => (other, "")
          }
          lineGenerator.endLine()

          val bodyFormatter =
            lineGenerator.derive(increaseIndent = true, newSpacing = Spacing.Off)
          bodyFormatter.beginLine()
          bodyFormatter.append(
              buildExpression(
                  expr,
                  placeholderOpen = Symbols.PlaceholderOpenTilde,
                  inStringOrCommand = true
              )
          )

          // Function to replace indenting in command block expressions with the current
          // indent level of the formatter
          val indentRegexp = s"\n${indent}".r
          val replacement = s"\n${bodyFormatter.currentIndent}"
          def replaceIndent(s: String): String = indentRegexp.replaceAllIn(s, replacement)

          if (numParts > 2) {
            command.parts.slice(1, command.parts.size - 1).foreach { expr =>
              bodyFormatter.append(
                  buildExpression(expr,
                                  placeholderOpen = Symbols.PlaceholderOpenTilde,
                                  inStringOrCommand = true,
                                  stringModifier = Some(replaceIndent))
              )
            }
          }
          bodyFormatter.append(
              buildExpression(
                  command.parts.last match {
                    case ValueString(s, wdlType, text) =>
                      ValueString(commandEndRegexp.replaceFirstIn(s, ""), wdlType, text)
                    case other => other
                  },
                  placeholderOpen = Symbols.PlaceholderOpenTilde,
                  inStringOrCommand = true,
                  stringModifier = Some(replaceIndent)
              )
          )
          bodyFormatter.endLine()
        }
      }

      lineGenerator.beginLine()
      lineGenerator.append(Literal(Symbols.CommandClose))
    }
  }

  private case class KVStatement(id: String, expr: Expr) extends BaseStatement {
    private val idToken = Literal(id)
    private val delimToken = Literal(Symbols.KeyValueDelimiter)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildExpression(expr)

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(Vector(SizedSequence(lhs), rhs))
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
          case v: Vector[InputDefinition] if v.nonEmpty => Some(InputsBlock(v))
          case _                                        => None

        }
        val decls = task.declarations match {
          case v: Vector[Declaration] if v.nonEmpty =>
            Some(Section(v.map { decl =>
              DeclarationStatement(decl.name, decl.wdlType, decl.expr)
            }))
          case _ => None
        }
        val outputs = task.outputs match {
          case v: Vector[OutputDefinition] if v.nonEmpty => Some(OutputsBlock(v))
          case _                                         => None
        }
        Vector(
            inputs,
            decls,
            Some(CommandBlock(task.command)),
            outputs,
            task.runtime.map(RuntimeBlock),
            task.meta.map(meta => MetaBlock(meta.kvs)),
            task.parameterMeta.map(paramMeta => MetaBlock(paramMeta.kvs))
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

  def generateElement(element: Element): Vector[String] = {
    val stmt = element match {
      case d: Document => DocumentSections(d)
      case t: Task     => TaskBlock(t)
      case w: Workflow => WorkflowBlock(w)
      case other =>
        throw new RuntimeException(s"Formatting element of type ${other.getClass} not supported")
    }
    val lineGenerator = LineGenerator()
    stmt.format(lineGenerator)
    lineGenerator.toVector
  }

  def generateDocument(document: Document): Vector[String] = {
    generateElement(document)
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
