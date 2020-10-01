package wdlTools.syntax.v1

import wdlTools.syntax.{CommentMap, SourceLocation, WdlVersion}
import wdlTools.util.FileNode

// A concrete syntax for the Workflow Description Language (WDL). This shouldn't be used
// outside this package. Please use the abstract syntax instead.
object ConcreteSyntax {
  sealed trait Element {
    val loc: SourceLocation // where in the source program does this element belong
  }
  sealed trait WorkflowElement extends Element
  sealed trait DocumentElement extends Element

  // type system
  sealed trait Type extends Element
  case class TypeOptional(t: Type, loc: SourceLocation) extends Type
  case class TypeArray(t: Type, nonEmpty: Boolean, loc: SourceLocation) extends Type
  case class TypeMap(k: Type, v: Type, loc: SourceLocation) extends Type
  case class TypePair(l: Type, r: Type, loc: SourceLocation) extends Type
  case class TypeString(loc: SourceLocation) extends Type
  case class TypeFile(loc: SourceLocation) extends Type
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
  case class ExprString(value: String, loc: SourceLocation) extends Expr
  case class ExprBoolean(value: Boolean, loc: SourceLocation) extends Expr
  case class ExprInt(value: Long, loc: SourceLocation) extends Expr
  case class ExprFloat(value: Double, loc: SourceLocation) extends Expr

  // represents strings with interpolation.
  // For example:
  //  "some string part ~{ident + ident} some string part after"
  case class ExprCompoundString(value: Vector[Expr], loc: SourceLocation) extends Expr
  case class ExprMember(key: Expr, value: Expr, loc: SourceLocation) extends Expr
  case class ExprMapLiteral(value: Vector[ExprMember], loc: SourceLocation) extends Expr
  case class ExprObjectLiteral(value: Vector[ExprMember], loc: SourceLocation) extends Expr
  case class ExprArrayLiteral(value: Vector[Expr], loc: SourceLocation) extends Expr

  case class ExprIdentifier(id: String, loc: SourceLocation) extends Expr

  // These are parts of string interpolation expressions like:
  //
  // ${true="--yes" false="--no" boolean_value}
  // ${default="foo" optional_value}
  // ${sep=", " array_value}
  //
  trait PlaceHolderPart extends Element
  // true="--yes"    false="--no"
  case class ExprPlaceholderPartEqual(b: Boolean, value: Expr, loc: SourceLocation)
      extends PlaceHolderPart
  // default="foo"
  case class ExprPlaceholderPartDefault(value: Expr, loc: SourceLocation) extends PlaceHolderPart
  // sep=", "
  case class ExprPlaceholderPartSep(value: Expr, loc: SourceLocation) extends PlaceHolderPart

  // These are full expressions of the same kind
  //
  // ${true="--yes" false="--no" boolean_value}
  // ${default="foo" optional_value}
  // ${sep=", " array_value}
  case class ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr, loc: SourceLocation) extends Expr
  case class ExprPlaceholderDefault(default: Expr, value: Expr, loc: SourceLocation) extends Expr
  case class ExprPlaceholderSep(sep: Expr, value: Expr, loc: SourceLocation) extends Expr

  case class ExprUnaryPlus(value: Expr, loc: SourceLocation) extends Expr
  case class ExprUnaryMinus(value: Expr, loc: SourceLocation) extends Expr
  case class ExprLor(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprLand(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprNegate(value: Expr, loc: SourceLocation) extends Expr
  case class ExprEqeq(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprLt(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprGte(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprNeq(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprLte(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprGt(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprAdd(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprSub(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprMod(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprMul(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprDivide(a: Expr, b: Expr, loc: SourceLocation) extends Expr
  case class ExprPair(l: Expr, r: Expr, loc: SourceLocation) extends Expr
  case class ExprAt(array: Expr, index: Expr, loc: SourceLocation) extends Expr
  case class ExprApply(funcName: String, elements: Vector[Expr], loc: SourceLocation) extends Expr
  case class ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, loc: SourceLocation)
      extends Expr
  case class ExprGetName(e: Expr, id: String, loc: SourceLocation) extends Expr

  case class Declaration(name: String, wdlType: Type, expr: Option[Expr], loc: SourceLocation)
      extends WorkflowElement

  // sections
  case class InputSection(declarations: Vector[Declaration], loc: SourceLocation) extends Element
  case class OutputSection(declarations: Vector[Declaration], loc: SourceLocation) extends Element

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

  // imports
  case class ImportAddr(value: String, loc: SourceLocation) extends Element
  case class ImportName(value: String, loc: SourceLocation) extends Element
  case class ImportAlias(id1: String, id2: String, loc: SourceLocation) extends Element

  // import statement as read from the document
  case class ImportDoc(name: Option[ImportName],
                       aliases: Vector[ImportAlias],
                       addr: ImportAddr,
                       loc: SourceLocation)
      extends DocumentElement

  // top level definitions
  case class Task(name: String,
                  input: Option[InputSection],
                  output: Option[OutputSection],
                  command: CommandSection, // the command section is required
                  declarations: Vector[Declaration],
                  meta: Option[MetaSection],
                  parameterMeta: Option[ParameterMetaSection],
                  runtime: Option[RuntimeSection],
                  loc: SourceLocation)
      extends DocumentElement

  case class CallAlias(name: String, loc: SourceLocation) extends Element
  case class CallInput(name: String, expr: Expr, loc: SourceLocation) extends Element
  case class CallInputs(value: Vector[CallInput], loc: SourceLocation) extends Element
  case class Call(name: String,
                  alias: Option[CallAlias],
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

  case class Workflow(name: String,
                      input: Option[InputSection],
                      output: Option[OutputSection],
                      meta: Option[MetaSection],
                      parameterMeta: Option[ParameterMetaSection],
                      body: Vector[WorkflowElement],
                      loc: SourceLocation)
      extends Element

  case class Version(value: WdlVersion = WdlVersion.V1, loc: SourceLocation) extends Element
  case class Document(source: FileNode,
                      version: Version,
                      elements: Vector[DocumentElement],
                      workflow: Option[Workflow],
                      loc: SourceLocation,
                      comments: CommentMap)
      extends Element
}
