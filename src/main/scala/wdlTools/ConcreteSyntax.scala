package wdlTools

// A parser based on a WDL grammar written by Patrick Magee. The tool
// underlying the grammar is Antlr4.
//

// A concrete abstract syntax for the Workflow Description Language (WDL)
object ConcreteSyntax {
  sealed trait Element
  sealed trait WorkflowElement extends Element

  // type system
  sealed trait Type extends Element
  case class TypeOptional(t: Type) extends Type
  case class TypeArray(t: Type, nonEmpty: Boolean) extends Type
  case class TypeMap(k: Type, v: Type) extends Type
  case class TypePair(l: Type, r: Type) extends Type
  case object TypeString extends Type
  case object TypeFile extends Type
  case object TypeBool extends Type
  case object TypeInt extends Type
  case object TypeFloat extends Type
  case class TypeIdentifier(id: String) extends Type
  case object TypeObject extends Type
  case class TypeStruct(name: String, members: Map[String, Type]) extends Type

  // expressions
  sealed trait Expr extends Element
  case class ExprString(value: String) extends Expr
  case class ExprFile(value: String) extends Expr
  case class ExprBool(value: Boolean) extends Expr
  case class ExprInt(value: Int) extends Expr
  case class ExprFloat(value: Double) extends Expr
  case class ExprMapLiteral(value: Map[Expr, Expr]) extends Expr
  case class ExprObjectLiteral(value: Map[Expr, Expr]) extends Expr
  case class ExprArrayLiteral(value: Vector[Expr]) extends Expr

  case class ExprIdentifier(id: String) extends Expr
  case class ExprPlaceholderEqual(value: Expr) extends Expr
  case class ExprPlaceholderDefault(value: Expr) extends Expr
  case class ExprPlaceholderSep(value: Expr) extends Expr

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
  case class ExprPair(l: Expr, r: Expr) extends Expr
  case class ExprAt(array: Expr, index: Expr) extends Expr
  case class ExprApply(funcName: String, elements: Vector[Expr]) extends Expr
  case class ExprNegate(value: Expr) extends Expr
  case class ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr) extends Expr
  case class ExprGetName(e: Expr, id: String) extends Expr

  case class Declaration(name: String, wdlType: Type, expr: Option[Expr]) extends WorkflowElement

  // sections
  case class InputSection(declarations: Vector[Declaration]) extends Element
  case class OutputSection(declarations: Vector[Declaration]) extends Element

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

  case class CommandPartString(value: String) extends Element
  case class CommandPartExpr(value: Vector[Expr]) extends Element
  case class CommandPartExprWithString(expr: Vector[Expr], text: String) extends Element
  case class CommandSection(start: String, parts: Vector[CommandPartExprWithString]) extends Element

  case class RuntimeKV(id: String, expr: Expr) extends Element
  case class RuntimeSection(kvs: Vector[RuntimeKV]) extends Element

  // meta section
  case class MetaKV(id: String, expr: Expr) extends Element
  case class ParameterMetaSection(kvs: Vector[MetaKV]) extends Element
  case class MetaSection(kvs: Vector[MetaKV]) extends Element

  // imports
  case class ImportAlias(id1: String, id2: String) extends Element

  // a path to a file or an http location
  //
  // examples:
  //   http://google.com/A.txt
  //   https://google.com/A.txt
  //   file://A/B.txt
  //   foo.txt
  case class URL(addr: String)

  // import statement as read from the document
  case class ImportDoc(name: String, aliases: Vector[ImportAlias], url: URL) extends WorkflowElement

  // the basic import-doc is replaced with the elaborated version after we
  // dive in and parse it.
  case class ImportDocElaborated(name: String, aliases: Vector[ImportAlias], doc: Document)
      extends WorkflowElement

  // top level definitions
  case class Task(name: String,
                  input: Option[InputSection],
                  output: Option[OutputSection],
                  command: Option[CommandSection],
                  decls: Vector[Declaration],
                  meta: Option[MetaSection],
                  parameterMeta: Option[ParameterMetaSection])
      extends Element

  case class CallAlias(name: String) extends Element
  case class CallInput(name: String, expr: Expr) extends Element
  case class CallInputs(value: Map[String, Expr]) extends Element
  case class Call(name: String, alias: Option[String], inputs: Map[String, Expr])
      extends WorkflowElement
  case class Scatter(identifier: String, expr: Expr, body: Vector[WorkflowElement])
      extends WorkflowElement
  case class Conditional(expr: Expr, body: Vector[WorkflowElement]) extends WorkflowElement

  case class Workflow(name: String,
                      input: Option[InputSection],
                      output: Option[OutputSection],
                      meta: Option[MetaSection],
                      body: Vector[WorkflowElement])
      extends Element

  case class Version(value: String) extends Element
  case class Document(version: String, elements: Vector[Element]) extends Element
}
