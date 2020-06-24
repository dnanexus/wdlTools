package wdlTools.types

import java.net.URL

import wdlTools.syntax.{CommentMap, TextSource, WdlVersion}

// A tree representing a WDL program with all of the types in place.
object TypedAbstractSyntax {
  type WdlType = WdlTypes.T
  type T_Function = WdlTypes.T_Function

  trait Element {
    val text: TextSource // where in the source program does this element belong
  }
  sealed trait WorkflowElement extends Element
  sealed trait DocumentElement extends Element

  // expressions
  sealed trait Expr extends Element {
    val wdlType: WdlType
  }

  // values
  case class ValueNull(wdlType: WdlType, text: TextSource) extends Expr
  case class ValueNone(wdlType: WdlType, text: TextSource) extends Expr
  case class ValueBoolean(value: Boolean, wdlType: WdlType, text: TextSource) extends Expr
  case class ValueInt(value: Int, wdlType: WdlType, text: TextSource) extends Expr
  case class ValueFloat(value: Double, wdlType: WdlType, text: TextSource) extends Expr
  case class ValueString(value: String, wdlType: WdlType, text: TextSource) extends Expr
  case class ValueFile(value: String, wdlType: WdlType, text: TextSource) extends Expr
  case class ValueDirectory(value: String, wdlType: WdlType, text: TextSource) extends Expr
  case class ExprIdentifier(id: String, wdlType: WdlType, text: TextSource) extends Expr

  // represents strings with interpolation. These occur only in command blocks.
  // For example:
  //  "some string part ~{ident + ident} some string part after"
  case class ExprCompoundString(value: Vector[Expr], wdlType: WdlType, text: TextSource)
      extends Expr

  case class ExprPair(l: Expr, r: Expr, wdlType: WdlType, text: TextSource) extends Expr
  case class ExprArray(value: Vector[Expr], wdlType: WdlType, text: TextSource) extends Expr
  case class ExprMap(value: Map[Expr, Expr], wdlType: WdlType, text: TextSource) extends Expr
  case class ExprObject(value: Map[Expr, Expr], wdlType: WdlType, text: TextSource) extends Expr

  // These are expressions of kind:
  //
  // ~{true="--yes" false="--no" boolean_value}
  // ~{default="foo" optional_value}
  // ~{sep=", " array_value}
  //
  sealed trait ExprPlaceholder extends Expr {
    val value: Expr
  }
  case class ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr, wdlType: WdlType, text: TextSource)
      extends ExprPlaceholder
  case class ExprPlaceholderDefault(default: Expr, value: Expr, wdlType: WdlType, text: TextSource)
      extends ExprPlaceholder
  case class ExprPlaceholderSep(sep: Expr, value: Expr, wdlType: WdlType, text: TextSource)
      extends ExprPlaceholder

  // operators on one argument
  sealed trait ExprOperator1 extends Expr {
    val value: Expr
  }
  case class ExprUnaryPlus(value: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator1
  case class ExprUnaryMinus(value: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator1
  case class ExprNegate(value: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator1

  // operators on two arguments
  sealed trait ExprOperator2 extends Expr {
    val a: Expr
    val b: Expr
  }
  case class ExprLor(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprLand(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprEqeq(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprLt(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprGte(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprNeq(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprLte(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprGt(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprAdd(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprSub(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprMod(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprMul(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2
  case class ExprDivide(a: Expr, b: Expr, wdlType: WdlType, text: TextSource) extends ExprOperator2

  // Access an array element at [index]
  case class ExprAt(array: Expr, index: Expr, wdlType: WdlType, text: TextSource) extends Expr

  // conditional:
  // if (x == 1) then "Sunday" else "Weekday"
  case class ExprIfThenElse(cond: Expr,
                            tBranch: Expr,
                            fBranch: Expr,
                            wdlType: WdlType,
                            text: TextSource)
      extends Expr

  // Apply a standard library function to arguments. For example:
  //   read_int("4")
  case class ExprApply(funcName: String,
                       funcWdlType: T_Function,
                       elements: Vector[Expr],
                       wdlType: WdlType,
                       text: TextSource)
      extends Expr

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  case class ExprGetName(e: Expr, id: String, wdlType: WdlType, text: TextSource) extends Expr

  case class Declaration(name: String, wdlType: WdlType, expr: Option[Expr], text: TextSource)
      extends WorkflowElement

  // sections

  /** In draft-2 there is no `input {}` block. Bound and unbound declarations may be mixed together
    * and bound declarations that require evaluation cannot be treated as inputs.
    *
    * This definition based on Cromwell wom.callable.Callable.InputDefinition. It
    * is easier to use a variant of this interface for backward compatibility with dxWDL.
    */
  sealed trait InputDefinition extends Element {
    val name: String
    val wdlType: WdlTypes.T
    val text: TextSource
  }

  // A compulsory input that has no default, and must be provided by the caller
  case class RequiredInputDefinition(name: String, wdlType: WdlTypes.T, text: TextSource)
      extends InputDefinition

  // An input that has a default and may be skipped by the caller
  case class OverridableInputDefinitionWithDefault(name: String,
                                                   wdlType: WdlTypes.T,
                                                   defaultExpr: Expr,
                                                   text: TextSource)
      extends InputDefinition

  // An input whose value should always be calculated from the default, and is
  // not allowed to be overridden.
  /*  case class FixedInputDefinitionWithDefault(name : String,
                                             wdlType : WdlTypes.T,
                                             defaultExpr : Expr,
                                             text : TextSource) */

  // an input that may be omitted by the caller. In that case the value will
  // be null (or None).
  case class OptionalInputDefinition(name: String, wdlType: WdlTypes.T_Optional, text: TextSource)
      extends InputDefinition

  case class OutputDefinition(name: String, wdlType: WdlTypes.T, expr: Expr, text: TextSource)
      extends Element

  // A workflow or a task.
  sealed trait Callable {
    val name: String
    val wdlType: WdlTypes.T_Callable
    val inputs: Vector[InputDefinition]
    val outputs: Vector[OutputDefinition]
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
  case class CommandSection(parts: Vector[Expr], text: TextSource) extends Element
  case class RuntimeSection(kvs: Map[String, Expr], text: TextSource) extends Element

  // A specialized JSON-like object language for meta values only.
  sealed trait MetaValue extends Element
  case class MetaValueNull(text: TextSource) extends MetaValue
  case class MetaValueBoolean(value: Boolean, text: TextSource) extends MetaValue
  case class MetaValueInt(value: Int, text: TextSource) extends MetaValue
  case class MetaValueFloat(value: Double, text: TextSource) extends MetaValue
  case class MetaValueString(value: String, text: TextSource) extends MetaValue
  case class MetaValueObject(value: Map[String, MetaValue], text: TextSource) extends MetaValue
  case class MetaValueArray(value: Vector[MetaValue], text: TextSource) extends MetaValue

  // the parameter sections have mappings from keys to json-like objects
  case class ParameterMetaSection(kvs: Map[String, MetaValue], text: TextSource) extends Element
  case class MetaSection(kvs: Map[String, MetaValue], text: TextSource) extends Element
  // hints section
  case class HintsSection(kvs: Map[String, MetaValue], text: TextSource) extends Element

  case class Version(value: WdlVersion, text: TextSource) extends Element

  // import statement with the typed-AST for the referenced document
  case class ImportAlias(id1: String, id2: String, referee: WdlTypes.T_Struct, text: TextSource)
      extends Element
  case class ImportDoc(namespace: String,
                       aliases: Vector[ImportAlias],
                       addr: String,
                       doc: Document,
                       text: TextSource)
      extends DocumentElement

  // a definition of a struct
  case class StructDefinition(name: String,
                              wdlType: WdlTypes.T_Struct,
                              members: Map[String, WdlType],
                              text: TextSource)
      extends DocumentElement

  // A task
  case class Task(name: String,
                  wdlType: WdlTypes.T_Task,
                  inputs: Vector[InputDefinition],
                  outputs: Vector[OutputDefinition],
                  command: CommandSection, // the command section is required
                  declarations: Vector[Declaration],
                  meta: Option[MetaSection],
                  parameterMeta: Option[ParameterMetaSection],
                  runtime: Option[RuntimeSection],
                  hints: Option[HintsSection],
                  text: TextSource)
      extends DocumentElement
      with Callable

  case class CallAfter(name: String, text: TextSource) extends Element

  // A call has three names - a fully-qualified name (FQN), an unqualified name
  // (UQN), and an "actual" name. The UQN is the callee name without any namespace.
  // The FQN is the callee name including dot-separated namespace(s). The actual
  // name is the alias if there is one, else the UQN.
  // Examples:
  //
  // fully-qualified-name      actual-name
  // -----                     ---
  // call lib.concat as foo    foo
  // call add                  add
  // call a.b.c                c
  //
  case class Call(unqualifiedName: String,
                  fullyQualifiedName: String,
                  wdlType: WdlTypes.T_Call,
                  callee: WdlTypes.T_Callable,
                  alias: Option[String],
                  afters: Vector[WdlTypes.T_Call],
                  actualName: String,
                  inputs: Map[String, Expr],
                  text: TextSource)
      extends WorkflowElement

  case class Scatter(identifier: String,
                     expr: Expr,
                     body: Vector[WorkflowElement],
                     text: TextSource)
      extends WorkflowElement

  case class Conditional(expr: Expr, body: Vector[WorkflowElement], text: TextSource)
      extends WorkflowElement

  // A workflow

  case class Workflow(name: String,
                      wdlType: WdlTypes.T_Workflow,
                      inputs: Vector[InputDefinition],
                      outputs: Vector[OutputDefinition],
                      meta: Option[MetaSection],
                      parameterMeta: Option[ParameterMetaSection],
                      body: Vector[WorkflowElement],
                      text: TextSource)
      extends Element
      with Callable

  case class Document(sourceUrl: Option[URL],
                      sourceCode: String,
                      version: Version,
                      elements: Vector[DocumentElement],
                      workflow: Option[Workflow],
                      text: TextSource,
                      comments: CommentMap)
      extends Element
}
