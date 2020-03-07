package wdlTools

// A parser based on a WDL grammar written by Patrick Magee. The tool
// underlying the grammar is Antlr4.
//

// we need these for safe casting, and reporting on errors
import reflect.ClassTag
import scala.reflect.runtime.universe.{TypeTag, typeOf}

import collection.JavaConverters._
import java.nio.ByteBuffer
import org.antlr.v4.runtime._
import org.openwdl.wdl.parser._
//import org.antlr.v4.runtime.tree.TerminalNode

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
  case class ImportDoc(url: String, name: String, aliases: Vector[ImportAlias]) extends Element

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

  private def getParser(inp: String): WdlParser = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(inp.getBytes()))
    val lexer: WdlLexer = new WdlLexer(CodePointCharStream.fromBuffer(codePointBuffer))
    val parser: WdlParser = new WdlParser(new CommonTokenStream(lexer))

    // TODO: how do we get human readable errors?
    //parser.removeErrorListeners()
    parser.setErrorHandler(new BailErrorStrategy())
    parser
  }

  def apply(buf: String, tracing: Boolean = false): Document = {
    val parser: WdlParser = getParser(buf)
    if (tracing)
      parser.setTrace(true)
    val visitor = new ConcreteSyntax()
    val doc = visitor.visit(parser.document)
    doc.asInstanceOf[Document]
  }
}

import ConcreteSyntax._
class ConcreteSyntax extends WdlParserBaseVisitor[Element] {

  // visit the children of [ctx], and cast to the expected type [T]. Provide a reasonable
  // report if there is an error.
  //
  // The complex construction with the implicit "tt" is so that we could print the class of the
  // expected class T.
  private def visitAndSafeCast[T: ClassTag](ctx: ParserRuleContext)(implicit tt: TypeTag[T]): T = {
    val child = visitChildren(ctx)
    if (child == null) {
      throw new Exception(s"child is null, expecting child of type ${typeOf[T]}")
    }

    val ct = implicitly[ClassTag[T]]
    child match {
      case ct(x) => x
      case _ =>
        val tok = ctx.start
        val line = tok.getLine()
        val col = tok.getCharPositionInLine()
        System.out.println(
            s"Parser internal error occurred in line=${line} column=${col} startToken=${tok.getText()}"
        )
        throw new Exception(
            s"${child} has wrong type ${child.getClass.getSimpleName}, expecting ${typeOf[T]}"
        )
    }
  }

  /*
struct
	: STRUCT Identifier LBRACE (unbound_decls)* RBRACE
	;
   */
  override def visitStruct(ctx: WdlParser.StructContext): TypeStruct = {
    val name = ctx.Identifier().getText()
    val members = ctx
      .unbound_decls()
      .asScala
      .map {
        case x =>
          val decl = visitUnbound_decls(x)
          decl.name -> decl.wdlType
      }
      .toMap
    TypeStruct(name, members)
  }

  /*
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: WdlParser.Map_typeContext): Type = {
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt)
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: WdlParser.Array_typeContext): Type = {
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = (ctx.PLUS() != null)
    TypeArray(t, nonEmpty)
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: WdlParser.Pair_typeContext): Type = {
    val lt: Type = visitWdl_type(ctx.wdl_type(0))
    val rt: Type = visitWdl_type(ctx.wdl_type(1))
    TypePair(lt, rt)
  }

  /*
type_base
	: array_type
	| map_type
	| pair_type
	| (STRING | FILE | BOOLEAN | OBJECT | INT | FLOAT | Identifier)
	;
   */
  override def visitType_base(ctx: WdlParser.Type_baseContext): Type = {
    // Can this be done with type matchin?
    if (ctx.STRING() != null)
      return TypeString
    if (ctx.FILE() != null)
      return TypeFile
    if (ctx.BOOLEAN() != null)
      return TypeBool
    if (ctx.OBJECT() != null)
      return TypeObject
    if (ctx.INT() != null) {
      return TypeInt
    }
    if (ctx.FLOAT() != null)
      return TypeFloat
    if (ctx.Identifier() != null)
      return TypeIdentifier(ctx.getText())

    // a compound type (array, map, pair)
    return visitAndSafeCast[Type](ctx)
  }

  /*
wdl_type
  : (type_base OPTIONAL | type_base)
  ;
   */
  override def visitWdl_type(ctx: WdlParser.Wdl_typeContext): Type = {
    visitAndSafeCast[Type](ctx)
  }

  // EXPRESSIONS

  override def visitNumber(ctx: WdlParser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText().toInt)
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText().toDouble)
    }
    throw new Exception(s"Not an integer nor a float ${ctx.getText()}")
  }

  /* expression_placeholder_option
  : BoolLiteral EQUAL (string | number)
  | DEFAULT EQUAL (string | number)
  | SEP EQUAL (string | number)
  ; */
  override def visitExpression_placeholder_option(
      ctx: WdlParser.Expression_placeholder_optionContext
  ): Expr = {
    val expr = visitAndSafeCast[Expr](ctx)
    if (ctx.BoolLiteral() != null) {
      ExprPlaceholderEqual(expr)
    }
    if (ctx.DEFAULT() != null) {
      ExprPlaceholderDefault(expr)
    }
    if (ctx.SEP() != null) {
      ExprPlaceholderSep(expr)
    }
    throw new Exception(s"Not one of three known variants of a placeholder ${ctx}")
  }

  // TODO
  /* dquote_string
  : DQUOTE DQuoteStringPart* (DQuoteStringPart* DQuoteCommandStart (expression_placeholder_option)* expr RBRACE DQuoteStringPart*)* DQUOTE
  ; */
//  override def visitDquote_string(ctx : WdlParser.Dquote_stringContext) : Expr = ???

  /* squote_string
  : SQUOTE SQuoteStringPart* (SQuoteStringPart* SQuoteCommandStart (expression_placeholder_option)* expr RBRACE SQuoteStringPart*)* SQUOTE
  ;*/
//  override def visitSquote_string(ctx : WdlParser.Squote_stringContext) : Expr = ???

  /* string
  : dquote_string
  | squote_string
  ;
   */
  override def visitString(ctx: WdlParser.StringContext): Expr = {
    return ExprString(ctx.getText())
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: WdlParser.Primitive_literalContext): Expr = {
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText().toLowerCase() == "true"
      return ExprBool(value)
    }
    if (ctx.Identifier() != null) {
      return ExprIdentifier(ctx.getText())
    }
    return visitAndSafeCast[Expr](ctx)
  }

  override def visitLor(ctx: WdlParser.LorContext): Expr = {
    val arg0: Expr = visitAndSafeCast[Expr](ctx.expr_infix0())
    val arg1: Expr = visitAndSafeCast[Expr](ctx.expr_infix1())
    ExprLor(arg0, arg1)
  }

  override def visitLand(ctx: WdlParser.LandContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix1())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix2())
    ExprLand(arg0, arg1)
  }

  override def visitEqeq(ctx: WdlParser.EqeqContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprEqeq(arg0, arg1)
  }
  override def visitLt(ctx: WdlParser.LtContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprLt(arg0, arg1)
  }

  override def visitGte(ctx: WdlParser.GteContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprGte(arg0, arg1)
  }

  override def visitNeq(ctx: WdlParser.NeqContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprNeq(arg0, arg1)
  }

  override def visitLte(ctx: WdlParser.LteContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprLte(arg0, arg1)
  }

  override def visitGt(ctx: WdlParser.GtContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix2())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix3())
    ExprGt(arg0, arg1)
  }

  override def visitAdd(ctx: WdlParser.AddContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix3())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix4())
    ExprAdd(arg0, arg1)
  }

  override def visitSub(ctx: WdlParser.SubContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix3())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix4())
    ExprSub(arg0, arg1)
  }

  override def visitMod(ctx: WdlParser.ModContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix4())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix5())
    ExprMod(arg0, arg1)
  }

  override def visitMul(ctx: WdlParser.MulContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix4())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix5())
    ExprMul(arg0, arg1)
  }

  override def visitDivide(ctx: WdlParser.DivideContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr_infix4())
    val arg1 = visitAndSafeCast[Expr](ctx.expr_infix5())
    ExprDivide(arg0, arg1)
  }

  override def visitPair_literal(ctx: WdlParser.Pair_literalContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr(0))
    val arg1 = visitAndSafeCast[Expr](ctx.expr(1))
    ExprPair(arg0, arg1)
  }

  override def visitLeft_name(ctx: WdlParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText()
    ExprIdentifier(id)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlParser.AtContext): Expr = {
    val array = visitAndSafeCast[Expr](ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index)
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText()
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements)
  }

  // | NOT expr #negate
  override def visitNegate(ctx: WdlParser.NegateContext): Expr = {
    val element = visitAndSafeCast[Expr](ctx.expr())
    ExprNegate(element)
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: WdlParser.Map_literalContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitAndSafeCast[Expr](x))
      .toVector

    val n = elements.size
    if (n % 2 != 0)
      throw new Exception("the expressions in a map must come in pairs")

    val m: Map[Expr, Expr] =
      Vector.tabulate(n / 2)(i => elements(2 * i) -> elements(2 * i + 1)).toMap
    ExprMapLiteral(m)
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: WdlParser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlParser.Get_nameContext): Expr = {
    val e = visitAndSafeCast[Expr](ctx.expr_core())
    val id = ctx.Identifier.getText()
    ExprGetName(e, id)
  }

  // | OBJECT_LITERAL LBRACE (primitive_literal COLON expr (COMMA primitive_literal COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: WdlParser.Object_literalContext): Expr = {
    val ids: Vector[Expr] = ctx
      .primitive_literal()
      .asScala
      .map(x => visitPrimitive_literal(x))
      .toVector
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprObjectLiteral((ids zip elements).toMap)
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: WdlParser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements)
  }

  override def visitExpr(ctx: WdlParser.ExprContext): Expr = {
    visitAndSafeCast[Expr](ctx)
  }

  /*
unbound_decls
	: wdl_type Identifier
	;
   */
  override def visitUnbound_decls(ctx: WdlParser.Unbound_declsContext): Declaration = {
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText()
    Declaration(name, wdlType, None)
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: WdlParser.Bound_declsContext): Declaration = {
    val wdlType: Type = visitAndSafeCast[Type](ctx.wdl_type())
    val name: String = ctx.Identifier().getText()
    val expr: Expr = visitExpr(ctx.expr())
    Declaration(name, wdlType, Some(expr))
  }

  /*
any_decls
	: unbound_decls
	| bound_decls
	;
   */
  override def visitAny_decls(ctx: WdlParser.Any_declsContext): Declaration = {
    visitAndSafeCast[Declaration](ctx)
  }

  /* meta_kv
   : Identifier COLON expr
   ; */
  override def visitMeta_kv(ctx: WdlParser.Meta_kvContext): MetaKV = {
    val id = ctx.Identifier().getText()
    val expr = visitExpr(ctx.expr)
    MetaKV(id, expr)
  }

  //  PARAMETERMETA LBRACE meta_kv* RBRACE #parameter_meta
  override def visitParameter_meta(ctx: WdlParser.Parameter_metaContext): ParameterMetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    ParameterMetaSection(kvs)
  }

  //  META LBRACE meta_kv* RBRACE #meta
  override def visitMeta(ctx: WdlParser.MetaContext): MetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    MetaSection(kvs)
  }

  /* task_runtime_kv
 : Identifier COLON expr
 ; */
  override def visitTask_runtime_kv(ctx: WdlParser.Task_runtime_kvContext): Element = {
    val id: String = ctx.Identifier.getText()
    val expr: Expr = visitAndSafeCast[Expr](ctx.expr())
    RuntimeKV(id, expr)
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: WdlParser.Task_runtimeContext): RuntimeSection = {
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitAndSafeCast[RuntimeKV](x))
      .toVector
    RuntimeSection(kvs)
  }

  /*
task_input
	: INPUT LBRACE (any_decls)* RBRACE
	;
   */
  override def visitTask_input(ctx: WdlParser.Task_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls)
  }

  /* task_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	; */
  override def visitTask_output(ctx: WdlParser.Task_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls)
  }

  /* task_command_string_part
    : CommandStringPart*
    ; */
  override def visitTask_command_string_part(
      ctx: WdlParser.Task_command_string_partContext
  ): CommandPartString = {
    val text: String = ctx
      .CommandStringPart()
      .asScala
      .map(x => x.getText())
      .mkString("")
    CommandPartString(text)
  }

  /* task_command_expr_part
    : StringCommandStart  (expression_placeholder_option)* expr RBRACE
    ; */
  override def visitTask_command_expr_part(
      ctx: WdlParser.Task_command_expr_partContext
  ): CommandPartExpr = {
    val elements = ctx
      .expression_placeholder_option()
      .asScala
      .map(x => visitExpression_placeholder_option(x).asInstanceOf[Expr])
      .toVector
    val expr = visitExpr(ctx.expr())
    CommandPartExpr(elements :+ expr)
  }

  /* task_command_expr_with_string
    : task_command_expr_part task_command_string_part
    ; */
  override def visitTask_command_expr_with_string(
      ctx: WdlParser.Task_command_expr_with_stringContext
  ): CommandPartExprWithString = {
    val exprPart: CommandPartExpr = visitTask_command_expr_part(ctx.task_command_expr_part())
    val stringPart: CommandPartString = visitTask_command_string_part(
        ctx.task_command_string_part()
    )
    CommandPartExprWithString(exprPart.value, stringPart.value)
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: WdlParser.Task_commandContext): CommandSection = {
    val start: CommandPartString = visitTask_command_string_part(ctx.task_command_string_part())
    val parts: Vector[CommandPartExprWithString] = ctx
      .task_command_expr_with_string()
      .asScala
      .map(x => visitTask_command_expr_with_string(x))
      .toVector
    CommandSection(start.value, parts)
  }

  // Each section should appear at most once. Make sure this is the case.
  private def atMostOneSection[T](sections: Vector[T]): Option[T] = {
    sections.size match {
      case 0 => None
      case 1 => Some(sections.head)
      case n =>
        val first = sections.head
        val sectionName = first.getClass.getSimpleName
        throw new Exception(
            s"section ${sectionName} appears ${n} times, it cannot appear more than once"
        )
    }
  }

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: WdlParser.TaskContext): Task = {
    val name = ctx.Identifier().getText()
    val elems = ctx.task_element().asScala.map(visitTask_element).toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: InputSection => x
    })
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: OutputSection => x
    })
    val command: Option[CommandSection] = atMostOneSection(elems.collect {
      case x: CommandSection => x
    })
    val decls: Vector[Declaration] = elems.collect {
      case x: Declaration => x
    }
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: MetaSection => x
    })
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: ParameterMetaSection => x
    })

    Task(name,
         input = input,
         output = output,
         command = command,
         decls = decls,
         meta = meta,
         parameterMeta = parameterMeta)
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: WdlParser.Import_aliasContext): ImportAlias = {
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText())
      .toVector
    ImportAlias(ids(0), ids(1))
  }

  /*
 import_doc
	: IMPORT string AS Identifier (import_alias)*
	;
   */
  override def visitImport_doc(ctx: WdlParser.Import_docContext): ImportDoc = {
    val url = ctx.string().getText
    val name = ctx.Identifier().getText()
    val aliases = ctx
      .import_alias()
      .asScala
      .map(x => visitImport_alias(x))
      .toVector
    ImportDoc(url, name, aliases)
  }

  /* inner_workflow_element
	: bound_decls
	| call
	| scatter
	| conditional
	; */

  /* call_alias
	: AS Identifier
	; */
  override def visitCall_alias(ctx: WdlParser.Call_aliasContext): CallAlias = {
    CallAlias(ctx.Identifier().getText)
  }

  /* call_input
	: Identifier EQUAL expr
	; */
  override def visitCall_input(ctx: WdlParser.Call_inputContext): CallInput = {
    val expr = visitExpr(ctx.expr())
    CallInput(ctx.Identifier().getText, expr)
  }

  /* call_inputs
	: INPUT COLON (call_input (COMMA call_input)*)
	; */
  override def visitCall_inputs(ctx: WdlParser.Call_inputsContext): CallInputs = {
    val inputs: Map[String, Expr] = ctx
      .call_input()
      .asScala
      .map {
        case x =>
          val inp = visitCall_input(x)
          inp.name -> inp.expr
      }
      .toMap
    CallInputs(inputs)
  }

  /* call_body
	: LBRACE call_inputs? RBRACE
	; */
  override def visitCall_body(ctx: WdlParser.Call_bodyContext): CallInputs = {
    if (ctx.call_inputs() == null)
      CallInputs(Map.empty)
    else
      visitCall_inputs(ctx.call_inputs())
  }

  /* call
	: CALL Identifier call_alias?  call_body?
	; */
  override def visitCall(ctx: WdlParser.CallContext): Call = {
    val name = ctx.Identifier.getText()

    val alias: Option[String] =
      if (ctx.call_alias() == null) None
      else Some(ctx.call_alias().getText())

    val inputs: Map[String, Expr] =
      if (ctx.call_body() == null) {
        Map.empty[String, Expr]
      } else {
        val cb = visitCall_body(ctx.call_body())
        cb.value
      }

    Call(name: String, alias: Option[String], inputs: Map[String, Expr])
  }

  /*
scatter
	: SCATTER LPAREN Identifier In expr RPAREN LBRACE inner_workflow_element* RBRACE
 ; */
  override def visitScatter(ctx: WdlParser.ScatterContext): Scatter = {
    val id = ctx.Identifier.getText()
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Scatter(id, expr, body)
  }

  /* conditional
	: IF LPAREN expr RPAREN LBRACE inner_workflow_element* RBRACE
	; */
  override def visitConditional(ctx: WdlParser.ConditionalContext): Conditional = {
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Conditional(expr, body)
  }

  /* workflow_input
	: INPUT LBRACE (any_decls)* RBRACE
	; */
  override def visitWorkflow_input(ctx: WdlParser.Workflow_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls)
  }

  /* workflow_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	;
   */
  override def visitWorkflow_output(ctx: WdlParser.Workflow_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls)
  }

  /* inner_workflow_element
	: bound_decls
	| call
	| scatter
	| conditional
	; */
  override def visitInner_workflow_element(
      ctx: WdlParser.Inner_workflow_elementContext
  ): WorkflowElement = {
    visitAndSafeCast[WorkflowElement](ctx)
  }

  /* workflow_element
	: workflow_input #input
	| workflow_output #output
	| inner_workflow_element #inner_element
	| meta_obj #meta_element
	;

workflow
	: WORKFLOW Identifier LBRACE workflow_element* RBRACE
	;
   */
  override def visitWorkflow(ctx: WdlParser.WorkflowContext): Workflow = {
    val name = ctx.Identifier().getText()
    val elems: Vector[WdlParser.Workflow_elementContext] = ctx.workflow_element().asScala.toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: WdlParser.InputContext =>
        visitWorkflow_input(x.workflow_input())
    })
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: WdlParser.OutputContext =>
        visitWorkflow_output(x.workflow_output())
    })
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: WdlParser.Meta_elementContext =>
        val m: WdlParser.Meta_objContext = x.meta_obj()
        visitMeta(m.asInstanceOf[WdlParser.MetaContext])
    })
    val wfElems: Vector[WorkflowElement] = elems.collect {
      case x: WdlParser.Inner_elementContext =>
        visitInner_workflow_element(x.inner_workflow_element())
    }

    Workflow(name, input, output, meta, wfElems)
  }

  /*
document_element
	: import_doc
	| struct
	| task
	| workflow
	;
   */
  override def visitDocument_element(ctx: WdlParser.Document_elementContext): Element = {
    visitChildren(ctx)
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: WdlParser.VersionContext): Version = {
    val value = ctx.RELEASE_VERSION().getText()
    Version(value)
  }

  /*
document
	: version document_element*
	;
   */
  override def visitDocument(ctx: WdlParser.DocumentContext): Document = {
    val version = visitVersion(ctx.version())

    val elems_raw: Vector[WdlParser.Document_elementContext] =
      ctx.document_element().asScala.toVector
    val elems = elems_raw.map(e => visitDocument_element(e)).toVector

    Document(version.value, elems)
  }
}
