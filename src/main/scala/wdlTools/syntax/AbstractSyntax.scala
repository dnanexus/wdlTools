package wdlTools.syntax

// An abstract syntax for the Workflow Description Language (WDL)
object AbstractSyntax {
  trait WorkflowElement
  trait DocumentElement

  // type system
  trait Type
  case class TypeOptional(t: Type) extends Type
  case class TypeArray(t: Type, nonEmpty: Boolean = false) extends Type
  case class TypeMap(k: Type, v: Type) extends Type
  case class TypePair(l: Type, r: Type) extends Type
  case object TypeString extends Type
  case object TypeFile extends Type
  case object TypeBoolean extends Type
  case object TypeInt extends Type
  case object TypeFloat extends Type
  case class TypeIdentifier(id: String) extends Type
  case object TypeObject extends Type
  case class TypeStruct(name: String, members: Map[String, Type]) extends Type with DocumentElement

  // expressions
  trait Expr

  // values
  trait Value extends Expr
  case class ValueString(value: String) extends Value
  case class ValueFile(value: String) extends Value
  case class ValueBoolean(value: Boolean) extends Value
  case class ValueInt(value: Int) extends Value
  case class ValueFloat(value: Double) extends Value

  case class ExprIdentifier(id: String) extends Expr

  // represents strings with interpolation.
  // For example:
  //  "some string part ~{ident + ident} some string part after"
  case class ExprCompoundString(value: Vector[Expr]) extends Expr
  case class ExprPair(l: Expr, r: Expr) extends Expr
  case class ExprArray(value: Vector[Expr]) extends Expr
  case class ExprMap(value: Map[Expr, Expr]) extends Expr
  case class ExprObject(value: Map[String, Expr]) extends Expr

  // These are expressions of kind:
  //
  // ~{true="--yes" false="--no" boolean_value}
  // ~{default="foo" optional_value}
  // ~{sep=", " array_value}
  case class ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr) extends Expr
  case class ExprPlaceholderDefault(default: Expr, value: Expr) extends Expr
  case class ExprPlaceholderSep(sep: Expr, value: Expr) extends Expr

  // operators on one argument
  case class ExprUniraryPlus(value: Expr) extends Expr
  case class ExprUniraryMinus(value: Expr) extends Expr
  case class ExprNegate(value: Expr) extends Expr

  // operators on two arguments
  case class ExprLor(a: Expr, b: Expr) extends Expr
  case class ExprLand(a: Expr, b: Expr) extends Expr
  case class ExprEqeq(a: Expr, b: Expr) extends Expr
  case class ExprLt(a: Expr, b: Expr) extends Expr
  case class ExprGte(a: Expr, b: Expr) extends Expr
  case class ExprNeq(a: Expr, b: Expr) extends Expr
  case class ExprLte(a: Expr, b: Expr) extends Expr
  case class ExprGt(a: Expr, b: Expr) extends Expr
  case class ExprAdd(a: Expr, b: Expr) extends Expr
  case class ExprSub(a: Expr, b: Expr) extends Expr
  case class ExprMod(a: Expr, b: Expr) extends Expr
  case class ExprMul(a: Expr, b: Expr) extends Expr
  case class ExprDivide(a: Expr, b: Expr) extends Expr

  // Access an array element at [index]
  case class ExprAt(array: Expr, index: Expr) extends Expr

  // conditional:
  // if (x == 1) then "Sunday" else "Weekday"
  case class ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr) extends Expr

  // Apply a standard library function to arguments. For example:
  //   read_int("4")
  case class ExprApply(funcName: String, elements: Vector[Expr]) extends Expr

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  case class ExprGetName(e: Expr, id: String) extends Expr

  case class Declaration(name: String, wdlType: Type, expr: Option[Expr]) extends WorkflowElement

  // sections
  case class InputSection(declarations: Vector[Declaration])
  case class OutputSection(declarations: Vector[Declaration])

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
  case class CommandSection(parts: Vector[Expr])

  case class RuntimeKV(id: String, expr: Expr)
  case class RuntimeSection(kvs: Vector[RuntimeKV])

  // meta section
  case class MetaKV(id: String, expr: Expr)
  case class ParameterMetaSection(kvs: Vector[MetaKV])
  case class MetaSection(kvs: Vector[MetaKV])

  // import statement with the AST for the referenced document
  case class ImportAlias(id1: String, id2: String)
  case class ImportDoc(name: Option[String], aliases: Vector[ImportAlias], url: URL, doc: Document)
      extends DocumentElement

  // A task
  case class Task(name: String,
                  input: Option[InputSection],
                  output: Option[OutputSection],
                  command: CommandSection, // the command section is required
                  declarations: Vector[Declaration],
                  meta: Option[MetaSection],
                  parameterMeta: Option[ParameterMetaSection],
                  runtime: Option[RuntimeSection])
      extends DocumentElement

  case class Call(name: String, alias: Option[String], inputs: Map[String, Expr])
      extends WorkflowElement

  case class Scatter(identifier: String, expr: Expr, body: Vector[WorkflowElement])
      extends WorkflowElement

  case class Conditional(expr: Expr, body: Vector[WorkflowElement]) extends WorkflowElement

  // A workflow
  case class Workflow(name: String,
                      input: Option[InputSection],
                      output: Option[OutputSection],
                      meta: Option[MetaSection],
                      parameterMeta: Option[ParameterMetaSection],
                      body: Vector[WorkflowElement])

  case class Document(version: String,
                      elements: Vector[DocumentElement],
                      workflow: Option[Workflow])
}
