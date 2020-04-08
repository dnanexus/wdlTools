package wdlTools.formatter

import java.net.URL

import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Comment, Parsers}
import wdlTools.util.{Options, Util, Verbosity}

import scala.collection.mutable

case class V1_0Formatter(opts: Options,
                         documents: mutable.Map[URL, Seq[String]] = mutable.Map.empty) {

  abstract class Group(prefix: Option[Atom] = None,
                       suffix: Option[Atom] = None,
                       wrapAll: Boolean = false)
      extends Atom {
    lazy val prefixLength: Int = prefix.map(_.length).getOrElse(0)
    lazy val suffixLength: Int = suffix.map(_.length).getOrElse(0)

    override def format(lineFormatter: LineFormatter): Unit = {
      val wrapAndDedentSuffix = if (prefix.isEmpty) {
        lineFormatter.endLine(wrap = true, Indenting.IfNotIndented)
        false
      } else if (prefixLength < lineFormatter.lengthRemaining) {
        lineFormatter.appendChunk(prefix.get)
        lineFormatter.endLine(wrap = true, Indenting.Always)
        true
      } else {
        lineFormatter.endLine(wrap = true, Indenting.IfNotIndented)
        val wrap = wrapAll || length > lineFormatter.lengthRemaining
        lineFormatter.appendChunk(prefix.get)
        if (wrap) {
          lineFormatter.endLine(wrap = true, Indenting.Always)
          true
        } else {
          false
        }
      }

      wrapBody(lineFormatter)

      if (suffix.isDefined) {
        if (wrapAndDedentSuffix || suffixLength > lineFormatter.lengthRemaining) {
          val indenting = if (wrapAndDedentSuffix) {
            Indenting.Dedent
          } else {
            Indenting.Never
          }
          lineFormatter.endLine(wrap = true, indenting = indenting)
        }
        lineFormatter.appendChunk(suffix.get)
      }
    }

    def wrapBody(lineFormatter: LineFormatter): Unit
  }

  case class Container(items: Seq[Atom],
                       delimiter: Token = Token.ArrayDelimiter,
                       prefix: Option[Atom] = None,
                       suffix: Option[Atom] = None,
                       wrapAll: Boolean = false)
      extends Group(prefix = prefix, suffix = suffix, wrapAll = wrapAll) {
    lazy val itemStr: String = items.mkString(s"${delimiter} ")
    lazy val itemLength: Int = itemStr.length

    override def toString: String = {
      val open = prefix.map(_.toString).getOrElse("")
      val close = prefix.map(_.toString).getOrElse("")
      s"${open}${itemStr}${close}"
    }

    override def length: Int = {
      itemLength + prefixLength + suffixLength
    }

    def wrapBody(lineFormatter: LineFormatter): Unit = {
      if (items.nonEmpty) {
        if (wrapAll || (items.length > 1 && itemLength > lineFormatter.lengthRemaining)) {
          items.foreach { atom =>
            lineFormatter.appendChunk(Adjacent(Vector(atom, delimiter)))
            lineFormatter.endLine(wrap = true, indenting = Indenting.IfNotIndented)
          }
        } else {
          lineFormatter.appendString(itemStr)
        }
      }
    }
  }

  case class KeyValue(key: Atom, value: Atom, delimiter: Token = Token.KeyValueDelimiter)
      extends Atom {
    override def toString: String = {
      s"${key}${delimiter.length} ${value}"
    }

    override def length: Int = {
      key.length + value.length + delimiter.length + 1
    }

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(Adjacent(Vector(key, delimiter)), value))
    }
  }

  object DataType {
    def buildDataType(name: Token,
                      inner1: Option[Atom] = None,
                      inner2: Option[Atom] = None,
                      quantifier: Option[Token] = None): Atom = {
      if (inner1.isDefined) {
        Container(
            Vector(inner1, inner2).flatten,
            prefix = Some(Adjacent(Vector(name, Token.TypeParamOpen))),
            suffix = Some(Adjacent(Vector(Some(Token.TypeParamClose), quantifier).flatten))
        )
      } else if (quantifier.isDefined) {
        Adjacent(Vector(name, quantifier.get))
      } else {
        name
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
        case TypeOptional(inner, _) => fromWdlType(inner, quantifier = Some(Token.Optional))
        case TypeArray(inner, nonEmpty, _) =>
          val quantifier = if (nonEmpty) {
            Some(Token.NonEmpty)
          } else {
            None
          }
          buildDataType(Token.ArrayType, Some(fromWdlType(inner)), quantifier = quantifier)
        case TypeMap(keyType, valueType, _) if isPrimitiveType(keyType) =>
          buildDataType(Token.MapType, Some(fromWdlType(keyType)), Some(fromWdlType(valueType)))
        case TypePair(left, right, _) =>
          buildDataType(Token.PairType, Some(fromWdlType(left)), Some(fromWdlType(right)))
        case TypeStruct(name, _, _, _) => Token(name)
        case TypeObject(_)             => Token.ObjectType
        case TypeString(_)             => Token.StringType
        case TypeBoolean(_)            => Token.BooleanType
        case TypeInt(_)                => Token.IntType
        case TypeFloat(_)              => Token.FloatType
        case other                     => throw new Exception(s"Unrecognized type $other")
      }
    }
  }

  case class Unirary(oper: Token, value: Atom) extends Atom {
    override def toString: String = {
      s"${oper}${value}"
    }

    override def length: Int = {
      oper.length + value.length
    }
  }

  case class Operation(oper: Token, lhs: Atom, rhs: Atom, grouped: Boolean = false)
      extends Group(prefix = if (grouped) {
        Some(Token.GroupOpen)
      } else {
        None
      }, suffix = if (grouped) {
        Some(Token.GroupClose)
      } else {
        None
      }, wrapAll = false) {
    override def toString: String = {
      val str = s"${lhs} ${oper} ${rhs}"
      if (grouped) {
        s"${Token.GroupOpen}${str}${Token.GroupClose}"
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
      lineFormatter.appendAll(Vector(lhs, oper, rhs), Wrapping.AsNeeded)
    }
  }

  case class Placeholder(value: Atom,
                         open: Token = Token.PlaceholderOpenDollar,
                         close: Token = Token.PlaceholderClose,
                         options: Option[Seq[Atom]] = None)
      extends Group(prefix = Some(open), suffix = Some(close), wrapAll = false) {

    override def toString: String = {
      val optionsStr = options
        .map(_.map { opt =>
          s"${opt} "
        }.mkString)
        .getOrElse("")
      s"${open}${optionsStr}${value}${close}"
    }

    override def length: Int = {
      value.length + open.length + close.length + options.map(_.map(_.length + 1).sum).getOrElse(0)
    }

    override def wrapBody(lineFormatter: LineFormatter): Unit = {
      if (options.isDefined) {
        lineFormatter.appendAll(options.get)
      }
      lineFormatter.appendChunk(value)
    }
  }

  def buildExpression(expr: Expr,
                      placeholderOpen: Token = Token.PlaceholderOpenDollar,
                      inString: Boolean = false,
                      inPlaceholder: Boolean = false,
                      inOperation: Boolean = false): Atom = {

    /**
      * Creates a Token or a StringLiteral, depending on whether we're already inside a string literal
      * @param value the value to wrap
      * @return an Atom
      */
    def stringOrToken(value: String): Atom = {
      if (inString && !inPlaceholder) {
        Token(value)
      } else {
        StringLiteral(value)
      }
    }

    /**
      * Builds an expression that occurs nested within another expression. By default, passes all the current
      * parameter values to the nested call.
      * @param nestedExpression the nested Expr
      * @param placeholderOpen override the current value of `placeholderOpen`
      * @param inString override the current value of `inString`
      * @param inPlaceholder override the current value of `inPlaceholder`
      * @param inOperation override the current value of `inOperation`
      * @return an Atom
      */
    def nested(nestedExpression: Expr,
               placeholderOpen: Token = placeholderOpen,
               inString: Boolean = inString,
               inPlaceholder: Boolean = inPlaceholder,
               inOperation: Boolean = inOperation): Atom = {
      buildExpression(nestedExpression,
                      placeholderOpen = placeholderOpen,
                      inString = inString,
                      inPlaceholder = inPlaceholder,
                      inOperation = inOperation)
    }

    def unirary(oper: Token, value: Expr): Atom = {
      Unirary(oper, nested(value, inOperation = true))
    }

    def operation(oper: Token, lhs: Expr, rhs: Expr): Atom = {
      Operation(oper,
                nested(lhs, inPlaceholder = inString, inOperation = true),
                nested(rhs, inPlaceholder = inString, inOperation = true),
                grouped = inOperation)
    }

    def option(name: Token, value: Expr): Atom = {
      Adjacent(Vector(name, Token.Assignment, nested(value, inPlaceholder = true)))
    }

    expr match {
      // literal values
      case ValueNull(_)           => Token.Null
      case ValueString(value, _)  => stringOrToken(value)
      case ValueFile(value, _)    => stringOrToken(value)
      case ValueBoolean(value, _) => Token(value.toString)
      case ValueInt(value, _)     => Token(value.toString)
      case ValueFloat(value, _)   => Token(value.toString)
      case ExprPair(left, right, _) if !(inString || inPlaceholder) =>
        Container(
            Vector(nested(left), nested(right)),
            prefix = Some(Token.GroupOpen),
            suffix = Some(Token.GroupClose)
        )
      case ExprArray(value, _) =>
        Container(
            value.map(nested(_)),
            prefix = Some(Token.ArrayLiteralOpen),
            suffix = Some(Token.ArrayLiteralClose)
        )
      case ExprMap(value, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(nested(k), nested(v))
            }.toVector,
            prefix = Some(Token.MapOpen),
            suffix = Some(Token.MapClose),
            wrapAll = true
        )
      case ExprObject(value, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(Token(k), nested(v))
            }.toVector,
            prefix = Some(Token.MapOpen),
            suffix = Some(Token.MapClose),
            wrapAll = true
        )
      // placeholders
      case ExprPlaceholderEqual(t, f, value, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(
                        Vector(
                            option(Token.TrueOption, t),
                            option(Token.FalseOption, f)
                        )
                    ))
      case ExprPlaceholderDefault(default, value, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Token.DefaultOption, default))))
      case ExprPlaceholderSep(sep, value, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Token.SepOption, sep))))
      case ExprCompoundString(value, _) if !inPlaceholder =>
        val atom = Adjacent(value.map(nested(_, inString = true)))
        if (inString) {
          atom
        } else {
          StringLiteral(atom)
        }
      // other expressions need to be wrapped in a placeholder if they
      // appear in a string or command block
      case other =>
        val atom = other match {
          case ExprUniraryPlus(value, _)  => unirary(Token.UnaryPlus, value)
          case ExprUniraryMinus(value, _) => unirary(Token.UnaryMinus, value)
          case ExprNegate(value, _)       => unirary(Token.LogicalNot, value)
          case ExprLor(a, b, _)           => operation(Token.LogicalOr, a, b)
          case ExprLand(a, b, _)          => operation(Token.LogicalAnd, a, b)
          case ExprEqeq(a, b, _)          => operation(Token.Equality, a, b)
          case ExprLt(a, b, _)            => operation(Token.LessThan, a, b)
          case ExprLte(a, b, _)           => operation(Token.LessThanOrEqual, a, b)
          case ExprGt(a, b, _)            => operation(Token.GreaterThan, a, b)
          case ExprGte(a, b, _)           => operation(Token.GreaterThanOrEqual, a, b)
          case ExprNeq(a, b, _)           => operation(Token.Inequality, a, b)
          case ExprAdd(a, b, _)           => operation(Token.Addition, a, b)
          case ExprSub(a, b, _)           => operation(Token.Subtraction, a, b)
          case ExprMul(a, b, _)           => operation(Token.Multiplication, a, b)
          case ExprDivide(a, b, _)        => operation(Token.Division, a, b)
          case ExprMod(a, b, _)           => operation(Token.Remainder, a, b)
          case ExprIdentifier(id, _) =>
            Token(id)
          case ExprAt(array, index, _) =>
            Container(
                Vector(nested(index, inPlaceholder = inString)),
                prefix =
                  Some(Adjacent(Vector(nested(array, inPlaceholder = inString), Token.IndexOpen))),
                suffix = Some(Token.IndexClose)
            )
          case ExprIfThenElse(cond, tBranch, fBranch, _) =>
            Spaced(
                Vector(
                    Token.If,
                    nested(cond, inOperation = true, inPlaceholder = inString),
                    Token.Then,
                    nested(tBranch, inOperation = true, inPlaceholder = inString),
                    Token.Else,
                    nested(fBranch, inOperation = true, inPlaceholder = inString)
                ),
                Wrapping.AsNeeded
            )
          case ExprApply(funcName, elements, _) =>
            Container(
                elements.map(nested(_, inPlaceholder = inString)),
                prefix = Some(Adjacent(Vector(Token(funcName), Token.FunctionCallOpen))),
                suffix = Some(Token.FunctionCallClose)
            )
          case ExprGetName(e, id, _) =>
            Adjacent(Vector(nested(e, inPlaceholder = inString), Token.Access, Token(id)))
          case other => throw new Exception(s"Unrecognized expression $other")
        }
        if (inString && !inPlaceholder) {
          Placeholder(atom, placeholderOpen)
        } else {
          atom
        }
    }
  }

  case class CommentSection(comment: Comment) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendComment(comment)
    }
  }

  case class VersionStatement(version: String) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(Token.Version, Token(version)), Wrapping.Never)
      lineFormatter.endLine()
    }
  }

  case class ImportStatement(importDoc: ImportDoc) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      if (importDoc.comment.isDefined) {
        lineFormatter.appendComment(importDoc.comment.get)
      }
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(Token.Import, StringLiteral(importDoc.url.toString)),
                              Wrapping.Never)
      if (importDoc.name.isDefined) {
        lineFormatter.appendAll(Vector(Token.As, Token(importDoc.name.get)), Wrapping.AsNeeded)
      }
      importDoc.aliases.foreach { alias =>
        lineFormatter.appendAll(Vector(Token.Alias, Token(alias.id1), Token.As, Token(alias.id2)),
                                Wrapping.Always)
      }
      lineFormatter.endLine()
    }
  }

  case class ImportsSection(imports: Seq[ImportDoc]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      imports.map(ImportStatement)
    }
  }

  case class DeclarationStatement(name: String,
                                  wdlType: Type,
                                  expr: Option[Expr] = None,
                                  comment: Option[Comment])
      extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      if (comment.isDefined) {
        lineFormatter.appendComment(comment.get)
      }
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(DataType.fromWdlType(wdlType), Token(name)))
      if (expr.isDefined) {
        lineFormatter.appendAll(Vector(Token.Assignment, buildExpression(expr.get)))
      }
      lineFormatter.endLine()
    }
  }

  case class MembersSection(members: Seq[StructMember]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      members.map {
        case StructMember(name, dt, _, comment) => DeclarationStatement(name, dt, None, comment)
      }.toVector
    }
  }

  case class DeclarationsSection(declarations: Seq[Declaration]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      declarations.map { decl =>
        DeclarationStatement(decl.name, decl.wdlType, decl.expr, decl.comment)
      }
    }
  }

  case class MetaKVStatement(id: String, expr: Expr, comment: Option[Comment]) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      lineFormatter.appendAll(
          Vector(Adjacent(Vector(Token(id), Token.KeyValueDelimiter)), buildExpression(expr))
      )
      lineFormatter.endLine()
    }
  }

  case class MetadataSection(metaKV: Seq[MetaKV]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      metaKV.map(kv => MetaKVStatement(kv.id, kv.expr, kv.comment))
    }
  }

  sealed abstract class BlockStatement(keyword: Token,
                                       clause: Option[Atom] = None,
                                       body: Option[Chunk],
                                       comment: Option[Comment])
      extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(Some(keyword), clause, Some(Token.BlockOpen)).flatten)
      if (body.isDefined) {
        lineFormatter.endLine()
        body.get.format(lineFormatter.indented())
        lineFormatter.beginLine()
      }
      lineFormatter.appendChunk(Token.BlockClose)
      lineFormatter.endLine()
    }
  }

  case class StructBlock(struct: TypeStruct)
      extends BlockStatement(Token.Struct,
                             Some(Token(struct.name)),
                             Some(MembersSection(struct.members)),
                             struct.comment)

  case class InputsBlock(inputs: InputSection)
      extends BlockStatement(Token.Input,
                             body = Some(DeclarationsSection(inputs.declarations)),
                             comment = inputs.comment)

  case class OutputsBlock(outputs: OutputSection)
      extends BlockStatement(Token.Output,
                             body = Some(DeclarationsSection(outputs.declarations)),
                             comment = outputs.comment)

  case class MetaBlock(meta: MetaSection)
      extends BlockStatement(Token.Meta,
                             body = Some(MetadataSection(meta.kvs)),
                             comment = meta.comment)

  case class ParameterMetaBlock(parameterMeta: ParameterMetaSection)
      extends BlockStatement(Token.ParameterMeta,
                             body = Some(MetadataSection(parameterMeta.kvs)),
                             comment = parameterMeta.comment)

  case class WorkflowElementBody(elements: Seq[WorkflowElement]) extends SectionsStatement {
    override def sections: Seq[Statement] = {
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

  case class CallInputsStatement(inputs: Map[String, Expr]) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      val args = inputs.map {
        case (lhs, rhs) => Spaced(Vector(Token(lhs), Token.Assignment, buildExpression(rhs)))
      }.toVector
      lineFormatter.beginLine()
      lineFormatter.appendAll(
          Vector(Adjacent(Vector(Token.Input, Token.KeyValueDelimiter)), Container(args))
      )
      lineFormatter.endLine()
    }
  }

  case class CallBlock(call: Call)
      extends BlockStatement(
          Token.Call,
          Some(if (call.alias.isDefined) {
            Spaced(Vector(Token(call.name), Token.As, Token(call.alias.get)))
          } else {
            Token(call.name)
          }),
          if (call.inputs.nonEmpty) {
            Some(CallInputsStatement(call.inputs))
          } else {
            None
          },
          call.comment
      )

  case class ScatterBlock(scatter: Scatter)
      extends BlockStatement(
          Token.Scatter,
          Some(Spaced(Vector(Token(scatter.identifier), Token.In, buildExpression(scatter.expr)))),
          Some(WorkflowElementBody(scatter.body)),
          scatter.comment
      )

  case class ConditionalBlock(conditional: Conditional)
      extends BlockStatement(Token.If,
                             Some(buildExpression(conditional.expr)),
                             Some(WorkflowElementBody(conditional.body)),
                             conditional.comment)

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
      extends BlockStatement(Token.Workflow,
                             Some(Token(workflow.name)),
                             Some(WorkflowSections(workflow)),
                             workflow.comment)

  private val commandStartRegexp = "^[\n\r]+".r
  private val commandEndRegexp = "\\s+$".r
  private val commandSingletonRegexp = "^[\n\r]*(.*?)\\s+$".r

  case class CommandBlock(command: CommandSection) extends Statement {
    override def formatChunks(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(Token.Command, Token.CommandOpen))
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
                  placeholderOpen = Token.PlaceholderOpenTilde,
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
                  placeholderOpen = Token.PlaceholderOpenTilde,
                  inString = true
              )
          )
          if (numParts > 2) {
            command.parts.slice(1, command.parts.size - 1).foreach { chunk =>
              bodyFormatter.appendChunk(
                  buildExpression(chunk,
                                  placeholderOpen = Token.PlaceholderOpenTilde,
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
                  placeholderOpen = Token.PlaceholderOpenTilde,
                  inString = true
              )
          )
        }
        bodyFormatter.endLine()
      }

      lineFormatter.beginLine()
      lineFormatter.appendChunk(Token.CommandClose)
      lineFormatter.endLine()
    }
  }

  case class RuntimeMetadataSection(runtimeKV: Seq[RuntimeKV]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      runtimeKV.map(kv => MetaKVStatement(kv.id, kv.expr, kv.comment))
    }
  }

  case class RuntimeBlock(runtime: RuntimeSection)
      extends BlockStatement(Token.Runtime,
                             body = Some(RuntimeMetadataSection(runtime.kvs)),
                             comment = runtime.comment)

  case class TaskSections(task: Task) extends Sections {
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

  case class TaskBlock(task: Task)
      extends BlockStatement(Token.Task,
                             Some(Token(task.name)),
                             Some(TaskSections(task)),
                             task.comment)

  case class FormatterDocument(document: Document) extends Sections {
    def format(): Seq[String] = {
      val imports: mutable.ArrayBuffer[ImportDoc] = mutable.ArrayBuffer.empty
      val structs: mutable.ArrayBuffer[TypeStruct] = mutable.ArrayBuffer.empty
      val tasks: mutable.ArrayBuffer[Task] = mutable.ArrayBuffer.empty

      document.elements.foreach {
        case imp: ImportDoc     => imports.append(imp)
        case struct: TypeStruct => structs.append(struct)
        case task: Task         => tasks.append(task)
      }

      statements.append(VersionStatement("1.0"))

      if (document.comment.isDefined) {
        statements.append(CommentSection(document.comment.get))
      }

      if (imports.nonEmpty) {
        statements.append(ImportsSection(imports))
      }

      structs.map(StructBlock)

      if (document.workflow.isDefined) {
        statements.append(WorkflowBlock(document.workflow.get))
      }

      if (tasks.nonEmpty) {
        tasks.foreach(task => statements.append(TaskBlock(task)))
      }

      if (opts.verbosity == Verbosity.Verbose) {
        println(Util.prettyFormat(statements))
      }

      val lineFormatter = DefaultLineFormatter()

      format(lineFormatter)

      lineFormatter.toSeq
    }
  }

  def formatDocument(doc: Document): Seq[String] = {
    FormatterDocument(doc).format()
  }

  def formatDocuments(url: URL): Unit = {
    Parsers(opts).getDocumentWalker[Seq[String]](url, documents).walk { (url, doc, results) =>
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
