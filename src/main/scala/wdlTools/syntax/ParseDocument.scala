package wdlTools.syntax

// Parse one document. Do not follow imports.

// we need these for safe casting, and reporting on errors
import reflect.ClassTag
import scala.reflect.runtime.universe.{TypeTag, typeOf}
import collection.JavaConverters._
import java.nio.ByteBuffer

import org.antlr.v4.runtime._
import org.openwdl.wdl.parser._
import ConcreteSyntax._
import wdlTools.util.{URL, Options}
import wdlTools.util.Verbosity.Quiet

object ParseDocument {
  private def getParser(inp: String, conf: Options): (ErrorListener, WdlParser) = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(inp.getBytes()))
    val lexer: WdlLexer = new WdlLexer(CodePointCharStream.fromBuffer(codePointBuffer))
    val parser: WdlParser = new WdlParser(new CommonTokenStream(lexer))

    // setting up our own error handling
    val errListener = ErrorListener(conf)
    lexer.removeErrorListeners()
    lexer.addErrorListener(errListener)
    parser.removeErrorListeners()
    parser.addErrorListener(errListener)

    (errListener, parser)
  }

  def apply(sourceCode: String, conf: Options): Document = {
    val (errListener, parser) = getParser(sourceCode, conf)
    if (conf.antlr4Trace)
      parser.setTrace(true)
    val visitor = new ParseDocument(conf)
    val document = visitor.visitDocument(parser.document)

    // check if any errors were found
    val errors: Vector[SyntaxError] = errListener.getAllErrors
    if (errors.nonEmpty) {
      if (conf.verbosity > Quiet) {
        for (err <- errors) {
          System.out.println(err)
        }
      }
      throw new Exception(s"${errors.size} syntax errors were found, stopping")
    }
    document
  }
}

case class ParseDocument(conf: Options) extends WdlParserBaseVisitor[Element] {

  private def makeWdlException(msg: String, ctx: ParserRuleContext): RuntimeException = {
    val tok = ctx.start
    val line = tok.getLine
    val col = tok.getCharPositionInLine
    new RuntimeException(s"${msg}  in line ${line} col ${col}")
  }

  // visit the children of [ctx], and cast to the expected type [T]. Provide a reasonable
  // report if there is an error.
  //
  // The complex construction with the implicit "tt" is so that we could print the class of the
  // expected class T.
  private def visitAndSafeCast[T: ClassTag](ctx: ParserRuleContext)(implicit tt: TypeTag[T]): T = {
    val child = visitChildren(ctx)
    if (child == null) {
      throw makeWdlException(s"child is null, expecting child of type ${typeOf[T]}", ctx)
    }

    val ct = implicitly[ClassTag[T]]
    child match {
      case ct(x) => x
      case _ =>
        val msg = s"${child} has wrong type ${child.getClass.getSimpleName}, expecting ${typeOf[T]}"
        throw makeWdlException(msg, ctx)
    }
  }

  /*
struct
	: STRUCT Identifier LBRACE (unbound_decls)* RBRACE
	;
   */
  override def visitStruct(ctx: WdlParser.StructContext): TypeStruct = {
    val sName = ctx.Identifier().getText
    val membersVec: Vector[(String, Type)] = ctx
      .unbound_decls()
      .asScala
      .map { x =>
        val decl = visitUnbound_decls(x)
        decl.name -> decl.wdlType
      }
      .toVector

    // check that each field appears once
    val members: Map[String, Type] = membersVec
      .foldLeft(Map.empty[String, Type]) {
        case (accu, (fieldName, wdlType)) =>
          accu.get(fieldName) match {
            case None => accu + (fieldName -> wdlType)
            case Some(_) =>
              throw makeWdlException(s"struct ${sName} has field ${fieldName} defined twice", ctx)
          }
      }
    TypeStruct(sName, members)
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
    val nonEmpty = ctx.PLUS() != null
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
    if (ctx.array_type() != null)
      return visitArray_type(ctx.array_type())
    if (ctx.map_type() != null)
      return visitMap_type(ctx.map_type())
    if (ctx.pair_type() != null)
      return visitPair_type(ctx.pair_type())
    if (ctx.STRING() != null)
      return TypeString
    if (ctx.FILE() != null)
      return TypeFile
    if (ctx.BOOLEAN() != null)
      return TypeBoolean
    if (ctx.OBJECT() != null)
      return TypeObject
    if (ctx.INT() != null)
      return TypeInt
    if (ctx.FLOAT() != null)
      return TypeFloat
    if (ctx.Identifier() != null)
      return TypeIdentifier(ctx.getText)
    throw makeWdlException("sanity: unrecgonized type case", ctx)
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
      return ExprInt(ctx.getText.toInt)
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble)
    }
    throw makeWdlException(s"Not an integer nor a float ${ctx.getText}", ctx)
  }

  /* expression_placeholder_option
  : BoolLiteral EQUAL (string | number)
  | DEFAULT EQUAL (string | number)
  | SEP EQUAL (string | number)
  ; */
  override def visitExpression_placeholder_option(
      ctx: WdlParser.Expression_placeholder_optionContext
  ): PlaceHolderPart = {
    val expr: Expr =
      if (ctx.string() != null)
        visitString(ctx.string())
      else if (ctx.number() != null)
        visitNumber(ctx.number())
      else
        throw makeWdlException("sanity: not a string or a number", ctx)

    if (ctx.BoolLiteral() != null) {
      val b = ctx.BoolLiteral().getText.toLowerCase() == "true"
      return ExprPlaceholderPartEqual(b, expr)
    }
    if (ctx.DEFAULT() != null) {
      return ExprPlaceholderPartDefault(expr)
    }
    if (ctx.SEP() != null) {
      return ExprPlaceholderPartSep(expr)
    }
    throw makeWdlException(s"Not one of three known variants of a placeholder", ctx)
  }

  // These are full expressions of the same kind
  //
  // ${true="--yes" false="--no" boolean_value}
  // ${default="foo" optional_value}
  // ${sep=", " array_value}
  private def parseEntirePlaceHolderExpression(placeHolders: Vector[PlaceHolderPart],
                                               expr: Expr,
                                               ctx: ParserRuleContext): Expr = {
    if (placeHolders.isEmpty) {
      // This is just an expression inside braces
      // ${1}
      // ${x + 3}
      return expr
    }

    // This is a place-holder such as
    //   ${default="foo" optional_value}
    //   ${sep=", " array_value}
    if (placeHolders.size == 1) {
      placeHolders.head match {
        case ExprPlaceholderPartDefault(default) =>
          return ExprPlaceholderDefault(default, expr)
        case ExprPlaceholderPartSep(sep) =>
          return ExprPlaceholderSep(sep, expr)
        case _ =>
          throw makeWdlException("invalid place holder", ctx)
      }
    }

    //   ${true="--yes" false="--no" boolean_value}
    if (placeHolders.size == 2) {
      (placeHolders(0), placeHolders(1)) match {
        case (ExprPlaceholderPartEqual(true, x), ExprPlaceholderPartEqual(false, y)) =>
          return ExprPlaceholderEqual(x, y, expr)
        case (ExprPlaceholderPartEqual(false, x), ExprPlaceholderPartEqual(true, y)) =>
          return ExprPlaceholderEqual(y, x, expr)
        case (ExprPlaceholderPartEqual(_, _), ExprPlaceholderPartEqual(_, _)) =>
          throw makeWdlException("invalid boolean place holder", ctx)
        case (_, _) =>
          throw makeWdlException("invalid place holder", ctx)
      }
    }

    throw makeWdlException("invalid place holder", ctx)
  }

  /* string_part
  : StringPart*
  ; */
  override def visitString_part(ctx: WdlParser.String_partContext): ExprCompoundString = {
    val parts: Vector[Expr] = ctx
      .StringPart()
      .asScala
      .map(x => ExprString(x.getText))
      .toVector
    ExprCompoundString(parts)
  }

  /* string_expr_part
  : StringCommandStart (expression_placeholder_option)* expr RBRACE
  ; */
  override def visitString_expr_part(ctx: WdlParser.String_expr_partContext): Expr = {
    val pHolder: Vector[PlaceHolderPart] = ctx
      .expression_placeholder_option()
      .asScala
      .map(visitExpression_placeholder_option)
      .toVector
    val expr = visitExpr(ctx.expr())
    parseEntirePlaceHolderExpression(pHolder, expr, ctx)
  }

  /* string_expr_with_string_part
  : string_expr_part string_part
  ; */
  override def visitString_expr_with_string_part(
      ctx: WdlParser.String_expr_with_string_partContext
  ): ExprCompoundString = {
    val exprPart = visitString_expr_part(ctx.string_expr_part())
    val strPart = visitString_part(ctx.string_part())
    ExprCompoundString(Vector(exprPart, strPart))
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: WdlParser.StringContext): Expr = {
    val stringPart = ExprString(ctx.string_part().getText)
    val exprPart: Vector[ExprCompoundString] = ctx
      .string_expr_with_string_part()
      .asScala
      .map(visitString_expr_with_string_part)
      .toVector
    val exprPart2: Vector[Expr] = exprPart.flatMap(_.value)
    if (exprPart2.isEmpty) {
      // A string  literal
      stringPart
    } else {
      // A string that includes interpolation
      ExprCompoundString(Vector(stringPart) ++ exprPart2)
    }
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: WdlParser.Primitive_literalContext): Expr = {
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      return ExprBoolean(value)
    }
    if (ctx.number() != null) {
      return visitNumber(ctx.number())
    }
    if (ctx.string() != null) {
      return visitString(ctx.string())
    }
    if (ctx.Identifier() != null) {
      return ExprIdentifier(ctx.getText)
    }
    throw makeWdlException("Not one of four supported variants of primitive_literal", ctx)
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

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: WdlParser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
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

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: WdlParser.Pair_literalContext): Expr = {
    val arg0 = visitAndSafeCast[Expr](ctx.expr(0))
    val arg1 = visitAndSafeCast[Expr](ctx.expr(1))
    ExprPair(arg0, arg1)
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
      throw makeWdlException("the expressions in a map must come in pairs", ctx)

    val m: Map[Expr, Expr] =
      Vector.tabulate(n / 2)(i => elements(2 * i) -> elements(2 * i + 1)).toMap
    ExprMapLiteral(m)
  }

  // | OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: WdlParser.Object_literalContext): Expr = {
    val ids: Vector[String] = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprObjectLiteral((ids zip elements).toMap)
  }

  // | NOT expr #negate
  override def visitNegate(ctx: WdlParser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr)
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: WdlParser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr)
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr)
    else
      throw makeWdlException("sanity", ctx)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlParser.AtContext): Expr = {
    val array = visitAndSafeCast[Expr](ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index)
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements)
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

  override def visitLeft_name(ctx: WdlParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id)
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlParser.Get_nameContext): Expr = {
    val e = visitAndSafeCast[Expr](ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id)
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
    val name: String = ctx.Identifier().getText
    Declaration(name, wdlType, None)
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: WdlParser.Bound_declsContext): Declaration = {
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText
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
    if (ctx.unbound_decls() != null)
      return visitUnbound_decls(ctx.unbound_decls())
    if (ctx.bound_decls() != null)
      return visitBound_decls(ctx.bound_decls())
    throw new Exception("sanity")
  }

  /* meta_kv
   : Identifier COLON expr
   ; */
  override def visitMeta_kv(ctx: WdlParser.Meta_kvContext): MetaKV = {
    val id = ctx.Identifier().getText
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
  override def visitTask_runtime_kv(ctx: WdlParser.Task_runtime_kvContext): RuntimeKV = {
    val id: String = ctx.Identifier.getText
    val expr: Expr = visitExpr(ctx.expr())
    RuntimeKV(id, expr)
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: WdlParser.Task_runtimeContext): RuntimeSection = {
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitTask_runtime_kv(x))
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
  ): ExprString = {
    val text: String = ctx
      .CommandStringPart()
      .asScala
      .map(x => x.getText)
      .mkString("")
    ExprString(text)
  }

  /* task_command_expr_part
    : StringCommandStart  (expression_placeholder_option)* expr RBRACE
    ; */
  override def visitTask_command_expr_part(
      ctx: WdlParser.Task_command_expr_partContext
  ): Expr = {
    val placeHolders: Vector[PlaceHolderPart] = ctx
      .expression_placeholder_option()
      .asScala
      .map(x => visitExpression_placeholder_option(x))
      .toVector
    val expr = visitExpr(ctx.expr())
    parseEntirePlaceHolderExpression(placeHolders, expr, ctx)
  }

  /* task_command_expr_with_string
    : task_command_expr_part task_command_string_part
    ; */
  override def visitTask_command_expr_with_string(
      ctx: WdlParser.Task_command_expr_with_stringContext
  ): ExprCompoundString = {
    val exprPart: Expr = visitTask_command_expr_part(ctx.task_command_expr_part())
    val stringPart: Expr = visitTask_command_string_part(
        ctx.task_command_string_part()
    )
    ExprCompoundString(Vector(exprPart, stringPart))
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: WdlParser.Task_commandContext): CommandSection = {
    val start: Expr = visitTask_command_string_part(ctx.task_command_string_part())
    val parts: Vector[Expr] = ctx
      .task_command_expr_with_string()
      .asScala
      .map(x => visitTask_command_expr_with_string(x))
      .toVector

    val allParts: Vector[Expr] = start +: parts

    // discard empty strings, and flatten compound vectors of strings
    val cleanedParts = allParts.flatMap {
      case ExprString(x) if x.isEmpty => Vector.empty
      case ExprCompoundString(v)      => v
      case other                      => Vector(other)
    }

    // TODO: do the above until reaching a fixed point

    CommandSection(cleanedParts)
  }

  // A that should appear zero or once. Make sure this is the case.
  private def atMostOneSection[T](sections: Vector[T],
                                  ctx: ParserRuleContext)(implicit tt: TypeTag[T]): Option[T] = {
    sections.size match {
      case 0 => None
      case 1 => Some(sections.head)
      case n =>
        val sectionName: String = typeOf[T].toString
        throw makeWdlException(
            s"section ${sectionName} appears ${n} times, it cannot appear more than once",
            ctx
        )
    }
  }

  // A section that must appear exactly once
  private def exactlyOneSection[T](sections: Vector[T],
                                   ctx: ParserRuleContext)(implicit tt: TypeTag[T]): T = {
    sections.size match {
      case 1 => sections.head
      case n =>
        val sectionName: String = typeOf[T].toString
        throw makeWdlException(
            s"section ${sectionName} appears ${n} times, it must appear exactly once",
            ctx
        )
    }
  }

  // check that the parameter meta section references only has variables declared in
  // the input or output sections.
  private def validateParamMeta(paramMeta: ParameterMetaSection,
                                inputSection: Option[InputSection],
                                outputSection: Option[OutputSection],
                                ctx: ParserRuleContext): Unit = {
    val inputVarNames: Set[String] =
      inputSection
        .map(_.declarations.map(_.name).toSet)
        .getOrElse(Set.empty)
    val outputVarNames: Set[String] =
      outputSection
        .map(_.declarations.map(_.name).toSet)
        .getOrElse(Set.empty)

    // make sure the input and output sections to not intersect
    val both = inputVarNames intersect outputVarNames
    if (both.nonEmpty)
      throw makeWdlException(s"${both} appears in both input and output sections", ctx)

    val ioVarNames = inputVarNames ++ outputVarNames

    paramMeta.kvs.foreach {
      case MetaKV(k, _) =>
        if (!(ioVarNames contains k))
          throw makeWdlException(s"parameter ${k} does not appear in the input or output sections",
                                 ctx)
    }
  }

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: WdlParser.TaskContext): Task = {
    val name = ctx.Identifier().getText
    val elems = ctx.task_element().asScala.map(visitTask_element).toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: InputSection => x
    }, ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: OutputSection => x
    }, ctx)
    val command: CommandSection = exactlyOneSection(elems.collect {
      case x: CommandSection => x
    }, ctx)
    val decls: Vector[Declaration] = elems.collect {
      case x: Declaration => x
    }
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: MetaSection => x
    }, ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: ParameterMetaSection => x
    }, ctx)
    val runtime: Option[RuntimeSection] = atMostOneSection(elems.collect {
      case x: RuntimeSection => x
    }, ctx)

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Task(name,
         input = input,
         output = output,
         command = command,
         declarations = decls,
         meta = meta,
         parameterMeta = parameterMeta,
         runtime = runtime)
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: WdlParser.Import_aliasContext): ImportAlias = {
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    ImportAlias(ids(0), ids(1))
  }

  /*
import_as
    : AS Identifier
    ;

 import_doc
	: IMPORT string import_as? (import_alias)*
	;
   */
  override def visitImport_doc(ctx: WdlParser.Import_docContext): ImportDoc = {
    val url = ctx.string().getText.replaceAll("\"", "")
    val name =
      if (ctx.import_as() == null)
        None
      else
        Some(ctx.import_as().Identifier().getText)

    val aliases = ctx
      .import_alias()
      .asScala
      .map(x => visitImport_alias(x))
      .toVector
    ImportDoc(name, aliases, URL(url))
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
      .map { x =>
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
    val name = ctx.Identifier.getText

    val alias: Option[String] =
      if (ctx.call_alias() == null) None
      else {
        val ca: CallAlias = visitCall_alias(ctx.call_alias())
        Some(ca.name)
      }

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
    val id = ctx.Identifier.getText
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
    if (ctx.bound_decls() != null)
      return visitBound_decls(ctx.bound_decls())
    if (ctx.call() != null)
      return visitCall(ctx.call())
    if (ctx.scatter() != null)
      return visitScatter(ctx.scatter())
    if (ctx.conditional() != null)
      return visitConditional(ctx.conditional())
    throw new Exception("sanity")
  }

  /*
workflow_element
	: workflow_input #input
	| workflow_output #output
	| inner_workflow_element #inner_element
	| parameter_meta #parameter_meta_element
	| meta #meta_element
	;

workflow
	: WORKFLOW Identifier LBRACE workflow_element* RBRACE
	;
   */
  override def visitWorkflow(ctx: WdlParser.WorkflowContext): Workflow = {
    val name = ctx.Identifier().getText
    val elems: Vector[WdlParser.Workflow_elementContext] = ctx.workflow_element().asScala.toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: WdlParser.InputContext =>
        visitWorkflow_input(x.workflow_input())
    }, ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: WdlParser.OutputContext =>
        visitWorkflow_output(x.workflow_output())
    }, ctx)
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: WdlParser.Meta_elementContext =>
        visitMeta(x.meta())
    }, ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: WdlParser.Parameter_meta_elementContext =>
        visitParameter_meta(x.parameter_meta())
    }, ctx)
    val wfElems: Vector[WorkflowElement] = elems.collect {
      case x: WdlParser.Inner_elementContext =>
        visitInner_workflow_element(x.inner_workflow_element())
    }

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Workflow(name, input, output, meta, parameterMeta, wfElems)
  }

  /*
document_element
	: import_doc
	| struct
	| task
	;
   */
  override def visitDocument_element(ctx: WdlParser.Document_elementContext): DocumentElement = {
    visitChildren(ctx).asInstanceOf[DocumentElement]
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: WdlParser.VersionContext): Version = {
    val value = ctx.RELEASE_VERSION().getText
    Version(value)
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: WdlParser.DocumentContext): Document = {
    val version = visitVersion(ctx.version())

    val elems: Vector[DocumentElement] =
      ctx
        .document_element()
        .asScala
        .map(e => visitDocument_element(e))
        .toVector

    val workflow =
      if (ctx.workflow() == null)
        None
      else
        Some(visitWorkflow(ctx.workflow()))

    Document(version.value, elems, workflow)
  }
}
