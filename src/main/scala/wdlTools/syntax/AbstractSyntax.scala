package wdlTools.syntax

import dx.util.FileNode

// An abstract syntax for the Workflow Description Language (WDL)
object AbstractSyntax {
  trait Element {
    val loc: SourceLocation // where in the source program does this element belong
  }
  sealed trait WorkflowElement extends Element
  sealed trait DocumentElement extends Element

  // type system
  sealed trait Type extends Element
  case class TypeOptional(t: Type, loc: SourceLocation) extends Type
  case class TypeArray(t: Type, nonEmpty: Boolean = false, loc: SourceLocation) extends Type
  case class TypeMap(k: Type, v: Type, loc: SourceLocation) extends Type
  case class TypePair(l: Type, r: Type, loc: SourceLocation) extends Type
  case class TypeString(loc: SourceLocation) extends Type
  case class TypeFile(loc: SourceLocation) extends Type
  case class TypeDirectory(loc: SourceLocation) extends Type
  case class TypeBoolean(loc: SourceLocation) extends Type
  case class TypeInt(loc: SourceLocation) extends Type
  case class TypeFloat(loc: SourceLocation) extends Type
  case class TypeIdentifier(id: String, loc: SourceLocation) extends Type
  case class TypeObject(loc: SourceLocation) extends Type
  case class StructMember(name: String, wdlType: Type, loc: SourceLocation) extends Element
  case class TypeStruct(name: String, members: Vector[StructMember], loc: SourceLocation)
      extends Type
      with DocumentElement

  // expressions
  sealed trait Expr extends Element

  // values
  sealed trait Value extends Expr
  case class ValueNone(loc: SourceLocation) extends Value
  case class ValueString(value: String, loc: SourceLocation) extends Value
  case class ValueBoolean(value: Boolean, loc: SourceLocation) extends Value
  case class ValueInt(value: Long, loc: SourceLocation) extends Value
  case class ValueFloat(value: Double, loc: SourceLocation) extends Value

  case class ExprIdentifier(id: String, loc: SourceLocation) extends Expr

  // represents strings with interpolation.
  // For example:
  //  "some string part ~{ident + ident} some string part after"
  case class ExprCompoundString(value: Vector[Expr], loc: SourceLocation) extends Expr
  case class ExprPair(l: Expr, r: Expr, loc: SourceLocation) extends Expr
  case class ExprArray(value: Vector[Expr], loc: SourceLocation) extends Expr
  case class ExprMember(key: Expr, value: Expr, loc: SourceLocation) extends Expr
  case class ExprMap(value: Vector[ExprMember], loc: SourceLocation) extends Expr
  case class ExprObject(value: Vector[ExprMember], loc: SourceLocation) extends Expr
  case class ExprStruct(name: String, members: Vector[ExprMember], loc: SourceLocation) extends Expr

  // These are expressions of kind:
  //
  // ~{true="--yes" false="--no" boolean_value}
  // ~{default="foo" optional_value}
  // ~{sep=", " array_value}
  case class ExprPlaceholderCondition(t: Expr, f: Expr, value: Expr, loc: SourceLocation)
      extends Expr
  case class ExprPlaceholderDefault(default: Expr, value: Expr, loc: SourceLocation) extends Expr
  case class ExprPlaceholderSep(sep: Expr, value: Expr, loc: SourceLocation) extends Expr

  // Access an array element at [index]
  case class ExprAt(array: Expr, index: Expr, loc: SourceLocation) extends Expr

  // conditional:
  // if (x == 1) then "Sunday" else "Weekday"
  case class ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, loc: SourceLocation)
      extends Expr

  // Apply a standard library function to arguments. For example:
  //   read_int("4")
  case class ExprApply(funcName: String, elements: Vector[Expr], loc: SourceLocation) extends Expr

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  case class ExprGetName(e: Expr, id: String, loc: SourceLocation) extends Expr

  case class Declaration(name: String, wdlType: Type, expr: Option[Expr], loc: SourceLocation)
      extends WorkflowElement

  // sections
  /** In draft-2 there is no `input {}` block. Bound and unbound declarations may be mixed together
    * and bound declarations that require evaluation cannot be treated as inputs. Thus, the draft-2
    * `InputSection` `SourceLocation` may overlap with other elements.
    */
  case class InputSection(parameters: Vector[Declaration], loc: SourceLocation) extends Element
  case class OutputSection(parameters: Vector[Declaration], loc: SourceLocation) extends Element

  // A command can be simple, with just one continuous string:
  //
  // command {
  //     ls
  // }
  //
  // It can also include several embedded expressions. For example:
  //
  // command <<<
  //     echo "hello world"
  //     ls ~{input_file}
  //     echo ~{input_string}
  // >>>
  case class CommandSection(parts: Vector[Expr], loc: SourceLocation) extends Element

  case class RuntimeKV(id: String, expr: Expr, loc: SourceLocation) extends Element
  case class RuntimeSection(kvs: Vector[RuntimeKV], loc: SourceLocation) extends Element

  // A specialized JSON-like object language for meta values only.
  sealed trait MetaValue extends Element
  case class MetaValueNull(loc: SourceLocation) extends MetaValue
  case class MetaValueBoolean(value: Boolean, loc: SourceLocation) extends MetaValue
  case class MetaValueInt(value: Long, loc: SourceLocation) extends MetaValue
  case class MetaValueFloat(value: Double, loc: SourceLocation) extends MetaValue
  case class MetaValueString(value: String, loc: SourceLocation) extends MetaValue
  case class MetaValueObject(value: Vector[MetaKV], loc: SourceLocation) extends MetaValue
  case class MetaValueArray(value: Vector[MetaValue], loc: SourceLocation) extends MetaValue
  // meta section
  case class MetaKV(id: String, value: MetaValue, loc: SourceLocation) extends Element
  case class ParameterMetaSection(kvs: Vector[MetaKV], loc: SourceLocation) extends Element
  case class MetaSection(kvs: Vector[MetaKV], loc: SourceLocation) extends Element
  // hints section
  case class HintsSection(kvs: Vector[MetaKV], loc: SourceLocation) extends Element

  case class Version(value: WdlVersion, loc: SourceLocation) extends Element

  // import statement with the AST for the referenced document
  case class ImportAddr(value: String, loc: SourceLocation) extends Element
  case class ImportName(value: String, loc: SourceLocation) extends Element
  case class ImportAlias(id1: String, id2: String, loc: SourceLocation) extends Element
  case class ImportDoc(name: Option[ImportName],
                       aliases: Vector[ImportAlias],
                       addr: ImportAddr,
                       doc: Option[Document],
                       loc: SourceLocation)
      extends DocumentElement

  // A task
  case class Task(name: String,
                  input: Option[InputSection],
                  output: Option[OutputSection],
                  command: CommandSection, // the command section is required
                  privateVariables: Vector[Declaration],
                  meta: Option[MetaSection],
                  parameterMeta: Option[ParameterMetaSection],
                  runtime: Option[RuntimeSection],
                  hints: Option[HintsSection],
                  loc: SourceLocation)
      extends DocumentElement

  case class CallAlias(name: String, loc: SourceLocation) extends Element
  case class CallAfter(name: String, loc: SourceLocation) extends Element
  case class CallInput(name: String, expr: Expr, loc: SourceLocation) extends Element
  case class CallInputs(value: Vector[CallInput], loc: SourceLocation) extends Element
  case class Call(name: String,
                  alias: Option[CallAlias],
                  afters: Vector[CallAfter],
                  inputs: Option[CallInputs],
                  loc: SourceLocation)
      extends WorkflowElement

  case class Scatter(identifier: String,
                     expr: Expr,
                     body: Vector[WorkflowElement],
                     loc: SourceLocation)
      extends WorkflowElement

  case class Conditional(expr: Expr, body: Vector[WorkflowElement], loc: SourceLocation)
      extends WorkflowElement

  // A workflow
  case class Workflow(name: String,
                      input: Option[InputSection],
                      output: Option[OutputSection],
                      meta: Option[MetaSection],
                      parameterMeta: Option[ParameterMetaSection],
                      body: Vector[WorkflowElement],
                      loc: SourceLocation)
      extends Element

  case class Document(source: FileNode,
                      version: Version,
                      elements: Vector[DocumentElement],
                      workflow: Option[Workflow],
                      loc: SourceLocation,
                      comments: CommentMap)
      extends Element
}
