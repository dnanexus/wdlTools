package wdlTools.types

import wdlTools.syntax.{CommentMap, Quoting, SourceLocation, WdlVersion}
import dx.util.FileNode

// Use SeqMap for all map attributes, which maintains insert
// order - this is important for the code formatter to be
// able to maintain the ordering of elements
import scala.collection.immutable.SeqMap

// A tree representing a WDL program with all of the types in place.
object TypedAbstractSyntax {
  type T_Function = WdlTypes.T_Function

  trait Element {
    val loc: SourceLocation // where in the source program does this element belong
  }
  sealed trait WorkflowElement extends Element
  sealed trait DocumentElement extends Element

  // expressions
  sealed trait Expr extends Element {
    val wdlType: WdlTypes.T
  }

  // values
  case class ValueNull(wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueNone(wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueBoolean(value: Boolean, wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueInt(value: Long, wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueFloat(value: Double, wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueString(value: String,
                         wdlType: WdlTypes.T,
                         quoting: Quoting.Quoting = Quoting.None)(val loc: SourceLocation)
      extends Expr
  case class ValueFile(value: String, wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr
  case class ValueDirectory(value: String, wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr
  case class ExprIdentifier(id: String, wdlType: WdlTypes.T)(val loc: SourceLocation) extends Expr

  // represents strings with interpolation. These occur only in command blocks.
  // For example:
  //  "some string part ~{ident + ident} some string part after"
  case class ExprCompoundString(value: Vector[Expr],
                                wdlType: WdlTypes.T,
                                quoting: Quoting.Quoting = Quoting.None)(val loc: SourceLocation)
      extends Expr

  case class ExprPair(left: Expr, right: Expr, wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr
  case class ExprArray(value: Vector[Expr], wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr
  case class ExprMap(value: SeqMap[Expr, Expr], wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr
  case class ExprObject(value: SeqMap[Expr, Expr], wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr

  // These are expressions of kind:
  //
  // ~{true="--yes" false="--no" boolean_value}
  // ~{default="foo" optional_value}
  // ~{sep=", " array_value}
  //
  case class ExprPlaceholder(trueOpt: Option[Expr],
                             falseOpt: Option[Expr],
                             sepOpt: Option[Expr],
                             defaultOpt: Option[Expr],
                             value: Expr,
                             wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr

  // Access an array element at [index]
  case class ExprAt(array: Expr, index: Expr, wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr

  // conditional:
  // if (x == 1) then "Sunday" else "Weekday"
  case class ExprIfThenElse(cond: Expr, trueExpr: Expr, falseExpr: Expr, wdlType: WdlTypes.T)(
      val loc: SourceLocation
  ) extends Expr

  // Apply a standard library function to arguments. For example:
  //   read_int("4")
  case class ExprApply(funcName: String,
                       funcWdlType: T_Function,
                       elements: Vector[Expr],
                       wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  case class ExprGetName(expr: Expr, id: String, wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends Expr

  sealed trait Variable extends Element {
    val name: String
    val wdlType: WdlTypes.T
  }

  // sections

  /**
    * A task/workflow input that may/must be overridden by the caller.
    *
    * In draft-2 there is no `input {}` block. Bound and unbound declarations may be
    * mixed together and bound declarations that require evaluation cannot be treated
    * as inputs.
    */
  sealed trait InputParameter extends Variable

  // A compulsory input that has no default, and must be provided by the caller
  case class RequiredInputParameter(name: String, wdlType: WdlTypes.T)(val loc: SourceLocation)
      extends InputParameter

  /**
    * An input that has a default and may be skipped by the caller.
    */
  case class OverridableInputParameterWithDefault(name: String,
                                                  wdlType: WdlTypes.T,
                                                  defaultExpr: Expr)(val loc: SourceLocation)
      extends InputParameter

  /**
    * An input that may be omitted by the caller. In that case the value will
    * be null (or None).
    */
  case class OptionalInputParameter(name: String, wdlType: WdlTypes.T_Optional)(
      val loc: SourceLocation
  ) extends InputParameter

  case class OutputParameter(name: String, wdlType: WdlTypes.T, expr: Expr)(val loc: SourceLocation)
      extends Variable

  /**
    * A variable definition and assignment that does not appear in the input or output section,
    * i.e. it is private to the task/workflow.
    */
  case class PrivateVariable(name: String, wdlType: WdlTypes.T, expr: Expr)(val loc: SourceLocation)
      extends WorkflowElement
      with Variable

  // A workflow or a task.
  sealed trait Callable extends Element {
    val name: String
    val wdlType: WdlTypes.T_Callable
    val inputs: Vector[InputParameter]
    val outputs: Vector[OutputParameter]
  }

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
  case class CommandSection(parts: Vector[Expr])(val loc: SourceLocation) extends Element
  case class RuntimeSection(kvs: SeqMap[String, Expr])(val loc: SourceLocation) extends Element

  // A specialized JSON-like object language for meta values only.
  sealed trait MetaValue extends Element
  case class MetaValueNull()(val loc: SourceLocation) extends MetaValue
  case class MetaValueBoolean(value: Boolean)(val loc: SourceLocation) extends MetaValue
  case class MetaValueInt(value: Long)(val loc: SourceLocation) extends MetaValue
  case class MetaValueFloat(value: Double)(val loc: SourceLocation) extends MetaValue
  case class MetaValueString(value: String, quoting: Quoting.Quoting = Quoting.None)(
      val loc: SourceLocation
  ) extends MetaValue
  case class MetaValueObject(value: SeqMap[String, MetaValue])(val loc: SourceLocation)
      extends MetaValue
  case class MetaValueArray(value: Vector[MetaValue])(val loc: SourceLocation) extends MetaValue

  // the parameter sections have mappings from keys to json-like objects
  case class MetaSection(kvs: SeqMap[String, MetaValue])(val loc: SourceLocation) extends Element

  case class Version(value: WdlVersion)(val loc: SourceLocation) extends Element

  // import statement with the typed-AST for the referenced document
  case class ImportAlias(id1: String, id2: String, referee: WdlTypes.T_Struct)(
      val loc: SourceLocation
  ) extends Element
  case class ImportDoc(namespace: String, aliases: Vector[ImportAlias], addr: String, doc: Document)(
      val loc: SourceLocation
  ) extends DocumentElement

  // a definition of a struct
  case class StructDefinition(name: String,
                              wdlType: WdlTypes.T_Struct,
                              members: SeqMap[String, WdlTypes.T])(val loc: SourceLocation)
      extends DocumentElement

  // A task
  case class Task(name: String,
                  wdlType: WdlTypes.T_Task,
                  inputs: Vector[InputParameter],
                  outputs: Vector[OutputParameter],
                  command: CommandSection, // the command section is required
                  privateVariables: Vector[PrivateVariable],
                  meta: Option[MetaSection],
                  parameterMeta: Option[MetaSection],
                  runtime: Option[RuntimeSection],
                  hints: Option[MetaSection])(val loc: SourceLocation)
      extends DocumentElement
      with Callable

  /**
    * A call has three names - a fully-qualified name (FQN), an unqualified name (UQN), and an "actual" name. The UQN is
    * the callee name without any namespace. The FQN is the callee name including dot-separated namespace(s). The actual
    * name is the alias if there is one, else the UQN.
    * @example
    * fullyQualifiedName        unqualifiedName    actualName
    * -----                     -----              ---
    * call lib.concat as foo    concat             foo
    * call add                  add                add
    * call a.b.c                c                  c
    */
  case class Call(unqualifiedName: String,
                  fullyQualifiedName: String,
                  wdlType: WdlTypes.T_Call,
                  callee: WdlTypes.T_Callable,
                  alias: Option[String],
                  afters: Vector[WdlTypes.T_Call],
                  actualName: String,
                  inputs: SeqMap[String, Expr])(val loc: SourceLocation)
      extends WorkflowElement

  sealed trait BlockElement extends WorkflowElement {
    val expr: Expr
    val body: Vector[WorkflowElement]
  }

  case class Scatter(identifier: String, expr: Expr, body: Vector[WorkflowElement])(
      val loc: SourceLocation
  ) extends BlockElement

  case class Conditional(expr: Expr, body: Vector[WorkflowElement])(val loc: SourceLocation)
      extends BlockElement

  // A workflow

  case class Workflow(name: String,
                      wdlType: WdlTypes.T_Workflow,
                      inputs: Vector[InputParameter],
                      outputs: Vector[OutputParameter],
                      meta: Option[MetaSection],
                      parameterMeta: Option[MetaSection],
                      body: Vector[WorkflowElement])(val loc: SourceLocation)
      extends Element
      with Callable

  case class Document(source: FileNode,
                      version: Version,
                      elements: Vector[DocumentElement],
                      workflow: Option[Workflow],
                      comments: CommentMap)(val loc: SourceLocation)
      extends Element
}
