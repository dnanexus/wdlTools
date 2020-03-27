package wdlTools.formatter

import java.net.URI

import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.Verbosity
import wdlTools.util.Verbosity._

import scala.collection.mutable

case class WDL10Formatter(verbosity: Verbosity = Verbosity.Normal,
                          indentation: String = " ",
                          indentStep: Int = 2,
                          maxLineWidth: Int = 100,
                          documents: mutable.Map[URI, Seq[String]] = mutable.Map.empty) {
  object Indenting extends Enumeration {
    type Indenting = Value
    val Always, IfNotIndented, Never = Value
  }
  import Indenting.Indenting

  object Wrapping extends Enumeration {
    type Wrapping = Value
    val Always, AsNeeded, Never = Value
  }
  import Wrapping.Wrapping

  trait Wrapable {
    def wrap(lineWrapper: LineFormatter): Unit
  }

  case class LineFormatter(defaultIndenting: Indenting = Indenting.IfNotIndented,
                           initialIndent: String = "",
                           lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty) {
    private val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
    private val indent: mutable.StringBuilder = new mutable.StringBuilder("")

    def indented(indenting: Indenting = defaultIndenting): LineFormatter = {
      LineFormatter(defaultIndenting = indenting,
                    initialIndent = initialIndent + (indentation * indentStep),
                    lines = lines)
    }

    def maybeIndent(indenting: Indenting = defaultIndenting): Unit = {
      if (indenting == Indenting.Always || (
              indenting == Indenting.IfNotIndented && indent.length == initialIndent.length
          )) {
        indent.append(indentation * indentStep)
      }
    }

    def atLineStart: Boolean = {
      currentLine.length == indent.length
    }

    def lengthRemaining: Int = {
      maxLineWidth - currentLine.length
    }

    def endLine(): Unit = {
      endLineUnlessEmpty(wrap = false)
      indent.clear()
      indent.append(initialIndent)
    }

    def endLineUnlessEmpty(wrap: Boolean, indenting: Indenting = defaultIndenting): Unit = {
      if (!atLineStart) {
        lines.append(currentLine.toString)
        currentLine.clear()
        if (wrap) {
          maybeIndent(indenting)
        }
      }
    }

    def endLineAndDedent(): Unit = {
      if (!atLineStart) {
        lines.append(currentLine.toString)
        currentLine.clear()
      }
      val indentLength = indent.length
      if (indentLength == indentStep) {
        indent.clear()
      } else if (indentLength > indentStep) {
        indent.delete(indentLength - indentStep, indentLength)
      }
    }

    def buildSubstring(
        atoms: Seq[Atom],
        builder: mutable.StringBuilder = new mutable.StringBuilder(maxLineWidth)
    ): StringBuilder = {
      atoms.foreach { atom =>
        if (builder.nonEmpty && !builder.last.isWhitespace) {
          builder.append(" ")
        }
        builder.append(atom.toString)
      }
      builder
    }

    def append(atom: Atom): Unit = {
      buildSubstring(Vector(atom), currentLine)
    }

    def appendAll(atoms: Seq[Atom], wrapping: Wrapping = Wrapping.AsNeeded): Unit = {
      if (wrapping == Wrapping.Never) {
        buildSubstring(atoms, currentLine)
      } else {
        val substr = buildSubstring(atoms)
        if (wrapping == Wrapping.Always || (wrapping == Wrapping.AsNeeded && lengthRemaining < substr.length + 1)) {
          endLineUnlessEmpty(wrap = true)
          if (substr.length + indent.length <= maxLineWidth) {
            currentLine.append(indent)
            currentLine.append(substr)
          } else {
            appendWrapped(atoms)
          }
        } else {
          currentLine.append(" ")
          currentLine.append(substr)
        }
      }
    }

    /**
      * Append a delimited sequence of atoms.
      * @param wrapping how to wrap the delimited items
      * @param items the delimited items
      * @param prefix a prefix before the delimited items
      * @param suffix a suffix after the delimited items
      */
    def appendDelimited(items: Seq[Atom],
                        wrapping: Wrapping = Wrapping.AsNeeded,
                        prefix: Option[Atom] = None,
                        suffix: Option[Atom] = None,
                        delimiter: Token = Token.ArrayDelimiter): Unit = {
      val delimited = Delimited(items, delimiter)
      val wrap = if (wrapping == Wrapping.Always) {
        true
      } else if (items.isEmpty) {
        false
      } else {
        val prefixLength = prefix.map(_.length).getOrElse(0)
        val suffixLength = suffix.map(_.length).getOrElse(0)
        prefixLength + delimited.length + suffixLength > lengthRemaining
      }
      if (!wrap) {
        append(Adjacent(Vector(prefix, Some(delimited), suffix).flatten))
      } else {
        if (prefix.isDefined) {
          append(prefix.get)
        }
        endLineUnlessEmpty(wrap = true, indenting = Indenting.Always)
        if (delimited.length + indent.length + indentStep <= maxLineWidth) {
          appendWrapped(Vector(delimited))
        }
        if (suffix.isDefined) {
          endLineAndDedent()
          append(suffix.get)
        }
      }
    }

    def appendWrapped(atoms: Seq[Atom]): Unit = {
      atoms.foreach {
        case wrappable: Wrapable => wrappable.wrap(lineWrapper = this)
        case atom =>
          val space = if (atLineStart) {
            ""
          } else {
            " "
          }
          if (lengthRemaining >= space.length + atom.length) {
            currentLine.append(space)
            currentLine.append(atom)
          } else {
            endLineUnlessEmpty(wrap = true)
            currentLine.append(atom)
          }
      }
    }
  }

  /**
    * A sequence of atoms separated by a space
    * @param atoms the atoms
    */
  case class Spaced(atoms: Atom*) extends Atom {
    override def toString: String = {
      atoms.mkString(" ")
    }

    override def length: Int = {
      atoms.map(_.length).sum + atoms.length - 1
    }
  }

  /**
    * A sequence of adjacent atoms (with no spacing or wrapping)
    * @param atoms the atoms
    */
  case class Adjacent(atoms: Seq[Atom]) extends Atom {
    override def toString: String = {
      atoms.mkString("")
    }

    override def length: Int = {
      atoms.map(_.length).sum
    }
  }

  case class Delimited(items: Seq[Atom], delimiter: Token = Token.ArrayDelimiter)
      extends Atom
      with Wrapable {
    override def toString: String = {
      items.mkString(s"${delimiter} ")
    }

    override def length: Int = {
      items.map(_.length).sum + ((items.size - 1) * (delimiter.length + 1))
    }

    override def wrap(lineWrapper: LineFormatter): Unit = {
      if (items.isEmpty) {
        Unit
      } else if (items.length == 1) {
        lineWrapper.append(items.head)
      } else {
        val delimitedItems =
          items.slice(0, items.length - 1).map { item =>
            Adjacent(Vector(item, delimiter))
          }
        if (length < lineWrapper.lengthRemaining) {
          lineWrapper.appendAll(delimitedItems ++ Vector(items.last), Wrapping.Never)
        } else {
          delimitedItems.foreach { atom =>
            lineWrapper.append(atom)
            lineWrapper.endLineUnlessEmpty(wrap = true, indenting = Indenting.IfNotIndented)
          }
          lineWrapper.append(items.last)
        }
      }
    }
  }

  /**
    * A data type.
    * @param name data type name
    * @param inner1 inner type 1
    * @param inner2 innter type 2 (for Map, Pair)
    * @param quantifier quantifier (? or +)
    */
  case class DataType(name: Token,
                      inner1: Option[DataType] = None,
                      inner2: Option[DataType] = None,
                      quantifier: Option[Token] = None)
      extends Atom
      with Wrapable {
    lazy val nameLength: Int = name.length
    lazy val innerLength: Int = (inner1.isDefined, inner2.isDefined) match {
      case (true, true)   => inner1.get.length + inner2.get.length + 4
      case (true, false)  => inner1.get.length + 2
      case (false, false) => 0
      case _              => throw new Exception(s"Invalid DataType $name")
    }
    lazy val quantifierLength: Int = quantifier.map(_.length).getOrElse(0)

    override def length: Int = {
      nameLength + innerLength + quantifierLength
    }

    override def toString: String = {
      val s = new StringBuilder()
      s.append(name)
      (inner1.isDefined, inner2.isDefined) match {
        case (true, true) =>
          s.append(Token.TypeParamOpen)
            .append(inner1.toString)
            .append(Token.TypeParamDelimiter)
            .append(" ")
            .append(inner2.toString)
            .append(Token.TypeParamClose)
        case (true, false) =>
          s.append(Token.TypeParamOpen)
            .append(inner1.toString)
            .append(Token.TypeParamClose)
        case (false, false) => Unit
        case _              => throw new Exception(s"Invalid DataType $name")
      }
      if (quantifier.isDefined) {
        s.append(quantifier)
      }
      s.toString
    }

    override def wrap(lineWrapper: LineFormatter): Unit = {
      if (length <= lineWrapper.lengthRemaining || innerLength == 0) {
        lineWrapper.append(this)
      } else {
        lineWrapper.appendDelimited(Vector(inner1, inner2).flatten,
                                    Wrapping.Always,
                                    prefix = Some(Adjacent(Vector(name, Token.TypeParamOpen))),
                                    suffix = Some(Token.TypeParamClose))
      }
    }
  }

  object DataType {
    val DataTypeObject: DataType = DataType(Token.Object)
    val DataTypeString: DataType = DataType(Token.String)
    val DataTypeBoolean: DataType = DataType(Token.Boolean)
    val DataTypeInt: DataType = DataType(Token.Int)
    val DataTypeFloat: DataType = DataType(Token.Float)

    def fromWdlType(wdlType: Type, quantifier: Option[Token] = None): DataType = {
      wdlType match {
        case TypeOptional(inner) => fromWdlType(inner, quantifier = Some(Token.Optional))
        case TypeArray(inner, nonEmpty) =>
          val quantifier = if (nonEmpty) {
            Some(Token.NonEmpty)
          } else {
            None
          }
          DataType(Token.Array, Some(fromWdlType(inner)), quantifier = quantifier)
        case TypeMap(keyType @ (TypeString | TypeBoolean | TypeInt | TypeFloat | TypeFile),
                     valueType) =>
          DataType(Token.Map, Some(fromWdlType(keyType)), Some(fromWdlType(valueType)))
        case TypePair(left, right) =>
          DataType(Token.Pair, Some(fromWdlType(left)), Some(fromWdlType(right)))
        case TypeStruct(name, _) => DataType(Token(name))
        case TypeObject          => DataTypeObject
        case TypeString          => DataTypeString
        case TypeBoolean         => DataTypeBoolean
        case TypeInt             => DataTypeInt
        case TypeFloat           => DataTypeFloat
        case other               => throw new Exception(s"Unrecognized type $other")
      }
    }
  }
//
//  case class Unary(oper: Token, value: Atom) extends Atom with Wrapable {
//    override def toString: String = {
//      s"${oper}${value}"
//    }
//
//    override def length: Int = {}
//
//    override def wrap(lineWrapper: LineFormatter): Unit = {}
//  }
//
//  case class Operation(oper: Token, lhs: Atom, rhs: Atom) extends Atom with Wrapable {
//    override def toString: String = {
//      s"${lhs} ${oper} ${rhs}"
//    }
//
//    override def length: Int = {}
//
//    override def wrap(lineWrapper: LineFormatter): Unit = {}
//  }
//
//  def buildExpression(expr: Expr, placeholderOpen: Token = Token.PlaceholderOpenDollar): Atom = {
//    def unary(oper: Token, value: Expr): Atom = {
//      Unary(oper, buildExpression(value))
//    }
//
//    def operation(oper: Token, lhs: Expr, rhs: Expr): Atom = {
//      Operation(oper, buildExpression(lhs), buildExpression(rhs))
//    }
//
//    expr match {
//      // literal values
//      case ValueString(value)  => StringLiteral(value)
//      case ValueFile(value)    => StringLiteral(value)
//      case ValueBoolean(value) => Token(value.toString)
//      case ValueInt(value)     => Token(value.toString)
//      case ValueFloat(value)   => Token(value.toString)
//      // operators
//      case ExprUnaryPlus(value)  => unary(Token.UnaryPlus, value)
//      case ExprUnaryMinus(value) => unary(Token.UnaryMinus, value)
//      case ExprNegate(value)     => unary(Token.LogicalNot, value)
//      case ExprLor(a, b)         => operation(Token.LogicalOr, a, b)
//      case ExprLand(a, b)        => operation(Token.LogicalAnd, a, b)
//      case ExprEqeq(a, b)        => operation(Token.Equality, a, b)
//      case ExprLt(a, b)          => operation(Token.LessThan, a, b)
//      case ExprLte(a, b)         => operation(Token.LessThanOrEqual, a, b)
//      case ExprGt(a, b)          => operation(Token.GreaterThan, a, b)
//      case ExprGte(a, b)         => operation(Token.GreaterThanOrEqual, a, b)
//      case ExprNeq(a, b)         => operation(Token.Inequality, a, b)
//      case ExprAdd(a, b)         => operation(Token.Addition, a, b)
//      case ExprSub(a, b)         => operation(Token.Subtraction, a, b)
//      case ExprMul(a, b)         => operation(Token.Multiplication, a, b)
//      case ExprDivide(a, b)      => operation(Token.Division, a, b)
//      case ExprMod(a, b)         => operation(Token.Remainder, a, b)
//      // interpolation
//      case ExprIdentifier(id) => Interpolation(Vector(Token(id)), placeholderOpen)
//      case ExprCompoundString(value) =>
//        Interpolation(value.map(buildExpression(_, placeholderOpen)))
//      case ExprPair(left, right) =>
//        Token.GroupOpen.visit(atomizer, atoms)
//        atomizeExpression(left)
//        Token.ArrayDelimiter.visit(atomizer, atoms)
//        atomizeExpression(right)
//        Token.GroupClose.visit(atomizer, atoms)
//      case ExprArray(value) =>
//        Token.ArrayLiteralOpen.visit(atomizer, atoms)
//        addDelimitedList(value)
//        Token.ArrayLiteralClose.visit(atomizer, atoms)
//      case ExprMap(value) =>
//        Token.MapOpen.visit(atomizer, atoms)
//        if (value.nonEmpty) {
//          def addMapElement(element: (Expr, Expr)): Unit = {
//            atomizeExpression(element._1)
//            Token.KeyValueDelimiter.visit(atomizer, atoms)
//            atomizeExpression(element._2)
//          }
//
//          addMapElement(value.head)
//          value.tail.foreach { element =>
//            Token.MemberDelimiter.visit(atomizer, atoms)
//            addMapElement(element)
//          }
//        }
//        Token.MapClose.visit(atomizer, atoms)
//      case ExprObject(value) =>
//        Token.ObjectOpen.visit(atomizer, atoms)
//        if (value.nonEmpty) {
//          def addObjectElement(element: (String, Expr)): Unit = {
//            Tokens(element._1).visit(atomizer, atoms)
//            Token.KeyValueDelimiter.visit(atomizer, atoms)
//            atomizeExpression(element._2)
//          }
//
//          addObjectElement(value.head)
//          value.tail.foreach { element =>
//            Token.MemberDelimiter.visit(atomizer, atoms)
//            addObjectElement(element)
//          }
//        }
//        Token.ObjectClose.visit(atomizer, atoms)
//      case ExprPlaceholderEqual(t, f, value) =>
//        placeholderOpen.visit(atomizer, atoms)
//        Token.TrueOption.visit(atomizer, atoms)
//        atomizeExpression(t)
//        Token.FalseOption.visit(atomizer, atoms)
//        atomizeExpression(f)
//        atomizeExpression(value)
//        Token.PlaceholderClose.visit(atomizer, atoms)
//      case ExprPlaceholderDefault(default, value) =>
//        placeholderOpen.visit(atomizer, atoms)
//        Token.DefaultOption.visit(atomizer, atoms)
//        atomizeExpression(default)
//        atomizeExpression(value)
//        Token.PlaceholderClose.visit(atomizer, atoms)
//      case ExprPlaceholderSep(sep, value) =>
//        placeholderOpen.visit(atomizer, atoms)
//        Token.SepOption.visit(atomizer, atoms)
//        atomizeExpression(sep)
//        atomizeExpression(value)
//        Token.PlaceholderClose.visit(atomizer, atoms)
//      // other expressions
//      case ExprAt(array, index) =>
//        atomizeExpression(array)
//        Token.ArrayLiteralOpen.visit(atomizer, atoms)
//        atomizeExpression(index)
//        Token.ArrayLiteralClose.visit(atomizer, atoms)
//      case ExprIfThenElse(cond, tBranch, fBranch) =>
//        Token.If.visit(atomizer, atoms)
//        atomizeExpression(cond)
//        Token.Then.visit(atomizer, atoms)
//        atomizeExpression(tBranch)
//        Token.Else.visit(atomizer, atoms)
//        atomizeExpression(fBranch)
//      case ExprApply(funcName, elements) =>
//        Tokens(funcName).visit(atomizer, atoms)
//        Token.FunctionCallOpen.visit(atomizer, atoms)
//        addDelimitedList(elements)
//        Token.FunctionCallClose.visit(atomizer, atoms)
//      case ExprGetName(e, id) =>
//        atomizeExpression(e)
//        Token.Access.visit(atomizer, atoms)
//        Tokens(id).visit(atomizer, atoms)
//      case other => throw new Exception(s"Unrecognized expression $other")
//    }
//  }

  trait Statement {
    def format(lineFormatter: LineFormatter): Unit
  }

  abstract class StatementGroup extends Statement {
    def statements: Seq[Statement]

    override def format(lineFormatter: LineFormatter): Unit = {
      statements.foreach(_.format(lineFormatter))
    }
  }

  sealed abstract class SectionsStatement extends Statement {
    def sections: Seq[Statement]

    override def format(lineFormatter: LineFormatter): Unit = {
      if (sections.nonEmpty) {
        sections.head.format(lineFormatter)
        sections.tail.foreach { section =>
          lineFormatter.lines.append("")
          section.format(lineFormatter)
        }
      }
    }
  }

  class Sections extends SectionsStatement {
    val statements: mutable.Buffer[Statement] = mutable.ArrayBuffer.empty

    lazy override val sections: Seq[Statement] = statements.toVector
  }

  case class VersionStatement(version: String) extends Statement {
    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(Token.Version, Token(version)), Wrapping.Never)
      lineFormatter.endLine()
    }
  }

  case class ImportStatement(importDoc: ImportDoc) extends Statement {
    override def format(lineFormatter: LineFormatter): Unit = {
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

  def buildExpression(expr: Expr, placeholderOpen: Token = Token.PlaceholderOpenDollar): Atom = {
    Token("x")
  }

  case class DeclarationStatement(name: String, wdlType: Type, expr: Option[Expr] = None)
      extends Statement {
    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(DataType.fromWdlType(wdlType), Token(name)))
      if (expr.isDefined) {
        lineFormatter.appendAll(Vector(Token.Assignment, buildExpression(expr.get)))
      }
      lineFormatter.endLine()
    }
  }

  case class MembersSection(members: Map[String, Type]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      members.map {
        case (name, dt) => DeclarationStatement(name, dt)
      }.toVector
    }
  }

  case class DeclarationsSection(declarations: Seq[Declaration]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      declarations.map { decl =>
        DeclarationStatement(decl.name, decl.wdlType, decl.expr)
      }
    }
  }

  case class MetaKVStatement(id: String, expr: Expr) extends Statement {
    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(
          Vector(Adjacent(Vector(Token(id), Token.KeyValueDelimiter)), buildExpression(expr))
      )
    }
  }

  case class MetadataSection(metaKV: Seq[MetaKV]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      metaKV.map(kv => MetaKVStatement(kv.id, kv.expr))
    }
  }

  sealed abstract class BlockStatement(keyword: Token,
                                       clause: Option[Atom] = None,
                                       body: Option[Statement])
      extends Statement {

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(Some(keyword), clause, Some(Token.BlockOpen)).flatten)
      if (body.isDefined) {
        lineFormatter.endLine()
        body.get.format(lineFormatter.indented())
      }
      lineFormatter.append(Token.BlockClose)
    }
  }

  case class StructBlock(struct: TypeStruct)
      extends BlockStatement(Token.Struct,
                             Some(Token(struct.name)),
                             Some(MembersSection(struct.members)))

  case class InputsBlock(inputs: InputSection)
      extends BlockStatement(Token.Input, body = Some(DeclarationsSection(inputs.declarations)))

  case class OutputsBlock(outputs: OutputSection)
      extends BlockStatement(Token.Output, body = Some(DeclarationsSection(outputs.declarations)))

  case class MetaBlock(meta: MetaSection)
      extends BlockStatement(Token.Meta, body = Some(MetadataSection(meta.kvs)))

  case class ParameterMetaBlock(parameterMeta: ParameterMetaSection)
      extends BlockStatement(Token.ParameterMeta, body = Some(MetadataSection(parameterMeta.kvs)))

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
    override def format(lineFormatter: LineFormatter): Unit = {
      val args = inputs.map {
        case (lhs, rhs) => Spaced(Token(lhs), Token.Assignment, buildExpression(rhs))
      }.toVector
      lineFormatter.appendAll(
          Vector(Adjacent(Vector(Token.Input, Token.KeyValueDelimiter)), Delimited(args))
      )
    }
  }

  case class CallBlock(call: Call)
      extends BlockStatement(
          Token.Call,
          Some(if (call.alias.isDefined) {
            Spaced(Token(call.name), Token.As, Token(call.alias.get))
          } else {
            Token(call.name)
          }),
          if (call.inputs.nonEmpty) {
            Some(CallInputsStatement(call.inputs))
          } else {
            None
          }
      )

  case class ScatterBlock(scatter: Scatter)
      extends BlockStatement(
          Token.Scatter,
          Some(Spaced(Token(scatter.identifier), Token.In, buildExpression(scatter.expr))),
          Some(WorkflowElementBody(scatter.body))
      )

  case class ConditionalBlock(conditional: Conditional)
      extends BlockStatement(Token.If,
                             Some(buildExpression(conditional.expr)),
                             Some(WorkflowElementBody(conditional.body)))

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
                             Some(WorkflowSections(workflow)))

  case class CommandBlock(command: CommandSection) extends Statement {
    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(Token.Command, Token.CommandOpen))
      lineFormatter.endLine()
      command.parts.foreach { expr =>
        lineFormatter.append(buildExpression(expr, Token.PlaceholderOpenTilde))
        lineFormatter.endLine()
      }
      lineFormatter.append(Token.CommandClose)
    }
  }

  case class RuntimeMetadataSection(runtimeKV: Seq[RuntimeKV]) extends StatementGroup {
    override def statements: Seq[Statement] = {
      runtimeKV.map(kv => MetaKVStatement(kv.id, kv.expr))
    }
  }

  case class RuntimeBlock(runtime: RuntimeSection)
      extends BlockStatement(Token.Runtime, body = Some(RuntimeMetadataSection(runtime.kvs)))

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
      extends BlockStatement(Token.Task, Some(Token(task.name)), Some(TaskSections(task)))

  case class FormattedDocument(uri: URI,
                               document: Document,
                               dependencies: mutable.Map[String, Document] = mutable.Map.empty)
      extends Sections {
    def apply(): Unit = {
      val imports: mutable.ArrayBuffer[ImportDoc] = mutable.ArrayBuffer.empty
      val structs: mutable.ArrayBuffer[TypeStruct] = mutable.ArrayBuffer.empty
      val tasks: mutable.ArrayBuffer[Task] = mutable.ArrayBuffer.empty

      document.elements.foreach {
        case imp: ImportDoc =>
          imports.append(imp)
          dependencies(imp.url.addr) = imp.doc
        case struct: TypeStruct => structs.append(struct)
        case task: Task         => tasks.append(task)
      }

      statements.append(VersionStatement("1.0"))

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

      val lineFormatter = LineFormatter()
      format(lineFormatter)

      documents(uri) = lineFormatter.lines.toVector
    }
  }

  def formatDocument(uri: URI, document: Document, followImports: Boolean = true): Unit = {
    val formattedDocument = FormattedDocument(uri, document)
    formattedDocument.apply()

    if (followImports && formattedDocument.dependencies.nonEmpty) {
      formattedDocument.dependencies.foreach {
        case (uriStr: String, document: Document) =>
          val uri = new URI(uriStr)
          if (!documents.contains(uri)) {
            formatDocument(uri, document, followImports)
          }
      }
    }
  }
}
//package wdlTools.formatter
//
//import com.sun.tools.javac.parser.Tokens
//
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
//
//  override def atomize(token: Token): Atom = {
//    defaults.getOrElse(token, FormatterToken(token))
//  }
//}
