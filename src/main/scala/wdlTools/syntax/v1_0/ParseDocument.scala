package wdlTools.syntax.v1_0

// Parse one document. Do not follow imports.

// we need these for safe casting, and reporting on errors
//import reflect.ClassTag

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.v1_0._
import wdlTools.syntax.Antlr4Util.{Grammar, GrammarFactory}
import wdlTools.syntax.v1_0.ConcreteSyntax._
import wdlTools.syntax.WdlVersion
import wdlTools.util.{Options, SourceCode, TextSource, URL}

import scala.collection.JavaConverters._

object ParseDocument {
  case class V1_0GrammarFactory(opts: Options)
      extends GrammarFactory[V10WdlLexer, V10WdlParser](opts) {
    override def createLexer(charStream: CharStream): V10WdlLexer = {
      new V10WdlLexer(charStream)
    }

    override def createParser(tokenStream: CommonTokenStream): V10WdlParser = {
      new V10WdlParser(tokenStream)
    }
  }

  def apply(sourceCode: SourceCode, opts: Options): Document = {
    val grammarFactory = V1_0GrammarFactory(opts)
    val grammar = grammarFactory.createGrammar(sourceCode.toString)
    val visitor = new ParseDocument(grammar, sourceCode.url, opts)
    val document = visitor.apply()
    grammar.verify()
    document
  }
}

case class ParseDocument(grammar: Grammar[V10WdlLexer, V10WdlParser],
                         docSourceURL: URL,
                         conf: Options)
    extends V10WdlParserBaseVisitor[Element] {

  private def makeWdlException(msg: String, ctx: ParserRuleContext): RuntimeException = {
    val tok = ctx.start
    val line = tok.getLine
    val col = tok.getCharPositionInLine
    new RuntimeException(s"${msg}  in line ${line} col ${col}")
  }

  private def getSourceText(ctx: ParserRuleContext): TextSource = {
    val tok = ctx.start
    val line = tok.getLine
    val col = tok.getCharPositionInLine
    TextSource(line = line, col = col, url = docSourceURL)
  }

  private def getSourceText(symbol: TerminalNode): TextSource = {
    val tok = symbol.getSymbol
    TextSource(line = tok.getLine, col = tok.getCharPositionInLine, url = docSourceURL)
  }

  /*
struct
	: STRUCT Identifier LBRACE (unbound_decls)* RBRACE
	;
   */
  override def visitStruct(ctx: V10WdlParser.StructContext): TypeStruct = {
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
    TypeStruct(sName, members, getSourceText(ctx))
  }

  /*
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: V10WdlParser.Map_typeContext): Type = {
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt, getSourceText(ctx))
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: V10WdlParser.Array_typeContext): Type = {
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = ctx.PLUS() != null
    TypeArray(t, nonEmpty, getSourceText(ctx))
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: V10WdlParser.Pair_typeContext): Type = {
    val lt: Type = visitWdl_type(ctx.wdl_type(0))
    val rt: Type = visitWdl_type(ctx.wdl_type(1))
    TypePair(lt, rt, getSourceText(ctx))
  }

  /*
type_base
	: array_type
	| map_type
	| pair_type
	| (STRING | FILE | BOOLEAN | OBJECT | INT | FLOAT | Identifier)
	;
   */
  override def visitType_base(ctx: V10WdlParser.Type_baseContext): Type = {
    if (ctx.array_type() != null)
      return visitArray_type(ctx.array_type())
    if (ctx.map_type() != null)
      return visitMap_type(ctx.map_type())
    if (ctx.pair_type() != null)
      return visitPair_type(ctx.pair_type())
    if (ctx.STRING() != null)
      return TypeString(getSourceText(ctx))
    if (ctx.FILE() != null)
      return TypeFile(getSourceText(ctx))
    if (ctx.BOOLEAN() != null)
      return TypeBoolean(getSourceText(ctx))
    if (ctx.OBJECT() != null)
      return TypeObject(getSourceText(ctx))
    if (ctx.INT() != null)
      return TypeInt(getSourceText(ctx))
    if (ctx.FLOAT() != null)
      return TypeFloat(getSourceText(ctx))
    if (ctx.Identifier() != null)
      return TypeIdentifier(ctx.getText, getSourceText(ctx))
    throw makeWdlException("sanity: unrecgonized type case", ctx)
  }

  /*
wdl_type
  : (type_base OPTIONAL | type_base)
  ;
   */
  override def visitWdl_type(ctx: V10WdlParser.Wdl_typeContext): Type = {
    visitChildren(ctx).asInstanceOf[Type]
  }

  // EXPRESSIONS

  override def visitNumber(ctx: V10WdlParser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText.toInt, getSourceText(ctx))
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble, getSourceText(ctx))
    }
    throw makeWdlException(s"Not an integer nor a float ${ctx.getText}", ctx)
  }

  /* expression_placeholder_option
  : BoolLiteral EQUAL (string | number)
  | DEFAULT EQUAL (string | number)
  | SEP EQUAL (string | number)
  ; */
  override def visitExpression_placeholder_option(
      ctx: V10WdlParser.Expression_placeholder_optionContext
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
      return ExprPlaceholderPartEqual(b, expr, getSourceText(ctx))
    }
    if (ctx.DEFAULT() != null) {
      return ExprPlaceholderPartDefault(expr, getSourceText(ctx))
    }
    if (ctx.SEP() != null) {
      return ExprPlaceholderPartSep(expr, getSourceText(ctx))
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
    val source = getSourceText(ctx)

    // This is a place-holder such as
    //   ${default="foo" optional_value}
    //   ${sep=", " array_value}
    if (placeHolders.size == 1) {
      placeHolders.head match {
        case ExprPlaceholderPartDefault(default, _) =>
          return ExprPlaceholderDefault(default, expr, source)
        case ExprPlaceholderPartSep(sep, _) =>
          return ExprPlaceholderSep(sep, expr, source)
        case _ =>
          throw makeWdlException("invalid place holder", ctx)
      }
    }

    //   ${true="--yes" false="--no" boolean_value}
    if (placeHolders.size == 2) {
      (placeHolders(0), placeHolders(1)) match {
        case (ExprPlaceholderPartEqual(true, x, _), ExprPlaceholderPartEqual(false, y, _)) =>
          return ExprPlaceholderEqual(x, y, expr, source)
        case (ExprPlaceholderPartEqual(false, x, _), ExprPlaceholderPartEqual(true, y, _)) =>
          return ExprPlaceholderEqual(y, x, expr, source)
        case (_: ExprPlaceholderPartEqual, _: ExprPlaceholderPartEqual) =>
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
  override def visitString_part(ctx: V10WdlParser.String_partContext): ExprCompoundString = {
    val parts: Vector[Expr] = ctx
      .StringPart()
      .asScala
      .map(x => ExprString(x.getText, getSourceText(x)))
      .toVector
    ExprCompoundString(parts, getSourceText(ctx))
  }

  /* string_expr_part
  : StringCommandStart (expression_placeholder_option)* expr RBRACE
  ; */
  override def visitString_expr_part(ctx: V10WdlParser.String_expr_partContext): Expr = {
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
      ctx: V10WdlParser.String_expr_with_string_partContext
  ): ExprCompoundString = {
    val exprPart = visitString_expr_part(ctx.string_expr_part())
    val strPart = visitString_part(ctx.string_part())
    ExprCompoundString(Vector(exprPart, strPart), getSourceText(ctx))
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: V10WdlParser.StringContext): Expr = {
    val stringPart = ExprString(ctx.string_part().getText, getSourceText(ctx.string_part()))
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
      ExprCompoundString(Vector(stringPart) ++ exprPart2, getSourceText(ctx))
    }
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: V10WdlParser.Primitive_literalContext): Expr = {
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      return ExprBoolean(value, getSourceText(ctx))
    }
    if (ctx.number() != null) {
      return visitNumber(ctx.number())
    }
    if (ctx.string() != null) {
      return visitString(ctx.string())
    }
    if (ctx.Identifier() != null) {
      return ExprIdentifier(ctx.getText, getSourceText(ctx))
    }
    throw makeWdlException("Not one of four supported variants of primitive_literal", ctx)
  }

  override def visitLor(ctx: V10WdlParser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, getSourceText(ctx))
  }

  override def visitLand(ctx: V10WdlParser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, getSourceText(ctx))
  }

  override def visitEqeq(ctx: V10WdlParser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, getSourceText(ctx))
  }
  override def visitLt(ctx: V10WdlParser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, getSourceText(ctx))
  }

  override def visitGte(ctx: V10WdlParser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, getSourceText(ctx))
  }

  override def visitNeq(ctx: V10WdlParser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, getSourceText(ctx))
  }

  override def visitLte(ctx: V10WdlParser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, getSourceText(ctx))
  }

  override def visitGt(ctx: V10WdlParser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, getSourceText(ctx))
  }

  override def visitAdd(ctx: V10WdlParser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, getSourceText(ctx))
  }

  override def visitSub(ctx: V10WdlParser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, getSourceText(ctx))
  }

  override def visitMod(ctx: V10WdlParser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, getSourceText(ctx))
  }

  override def visitMul(ctx: V10WdlParser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, getSourceText(ctx))
  }

  override def visitDivide(ctx: V10WdlParser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, getSourceText(ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: V10WdlParser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: V10WdlParser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, getSourceText(ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: V10WdlParser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, getSourceText(ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: V10WdlParser.Map_literalContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector

    val n = elements.size
    if (n % 2 != 0)
      throw makeWdlException("the expressions in a map must come in pairs", ctx)

    val m: Map[Expr, Expr] =
      Vector.tabulate(n / 2)(i => elements(2 * i) -> elements(2 * i + 1)).toMap
    ExprMapLiteral(m, getSourceText(ctx))
  }

  // | OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: V10WdlParser.Object_literalContext): Expr = {
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
    ExprObjectLiteral((ids zip elements).toMap, getSourceText(ctx))
  }

  // | NOT expr #negate
  override def visitNegate(ctx: V10WdlParser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, getSourceText(ctx))
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: V10WdlParser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr, getSourceText(ctx))
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr, getSourceText(ctx))
    else
      throw makeWdlException("sanity", ctx)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: V10WdlParser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, getSourceText(ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: V10WdlParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, getSourceText(ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: V10WdlParser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), getSourceText(ctx))
  }

  override def visitLeft_name(ctx: V10WdlParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id, getSourceText(ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: V10WdlParser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, getSourceText(ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: V10WdlParser.Expr_infix0Context): Expr = {
    ctx match {
      case lor: V10WdlParser.LorContext       => visitLor(lor)
      case infix1: V10WdlParser.Infix1Context => visitInfix1(infix1).asInstanceOf[Expr]
      case _                                  => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: V10WdlParser.Expr_infix1Context): Expr = {
    ctx match {
      case land: V10WdlParser.LandContext     => visitLand(land)
      case infix2: V10WdlParser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
      case _                                  => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix2
	: expr_infix2 EQUALITY expr_infix3 #eqeq
	| expr_infix2 NOTEQUAL expr_infix3 #neq
	| expr_infix2 LTE expr_infix3 #lte
	| expr_infix2 GTE expr_infix3 #gte
	| expr_infix2 LT expr_infix3 #lt
	| expr_infix2 GT expr_infix3 #gt
	| expr_infix3 #infix3
	; */

  private def visitExpr_infix2(ctx: V10WdlParser.Expr_infix2Context): Expr = {
    ctx match {
      case eqeq: V10WdlParser.EqeqContext     => visitEqeq(eqeq)
      case neq: V10WdlParser.NeqContext       => visitNeq(neq)
      case lte: V10WdlParser.LteContext       => visitLte(lte)
      case gte: V10WdlParser.GteContext       => visitGte(gte)
      case lt: V10WdlParser.LtContext         => visitLt(lt)
      case gt: V10WdlParser.GtContext         => visitGt(gt)
      case infix3: V10WdlParser.Infix3Context => visitInfix3(infix3).asInstanceOf[Expr]
      case _                                  => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: V10WdlParser.Expr_infix3Context): Expr = {
    ctx match {
      case add: V10WdlParser.AddContext       => visitAdd(add)
      case sub: V10WdlParser.SubContext       => visitSub(sub)
      case infix4: V10WdlParser.Infix4Context => visitInfix4(infix4).asInstanceOf[Expr]
      case _                                  => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: V10WdlParser.Expr_infix4Context): Expr = {
    ctx match {
      case mul: V10WdlParser.MulContext       => visitMul(mul)
      case divide: V10WdlParser.DivideContext => visitDivide(divide)
      case mod: V10WdlParser.ModContext       => visitMod(mod)
      case infix5: V10WdlParser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
//      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */

  override def visitExpr_infix5(ctx: V10WdlParser.Expr_infix5Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: V10WdlParser.ExprContext): Expr = {
    visitChildren(ctx).asInstanceOf[Expr]
  }

  /* expr_core
	: LPAREN expr RPAREN #expression_group
	| primitive_literal #primitives
	| LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
	| LPAREN expr COMMA expr RPAREN #pair_literal
	| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
	| OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
	| NOT expr #negate
	| (PLUS | MINUS) expr #unirarysigned
	| expr_core LBRACK expr RBRACK #at
	| IF expr THEN expr ELSE expr #ifthenelse
	| Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
	| Identifier #left_name
	| expr_core DOT Identifier #get_name
	; */
  private def visitExpr_core(ctx: V10WdlParser.Expr_coreContext): Expr = {
    ctx match {
      case group: V10WdlParser.Expression_groupContext => visitExpression_group(group)
      case primitives: V10WdlParser.PrimitivesContext =>
        visitChildren(primitives).asInstanceOf[Expr]
      case array_literal: V10WdlParser.Array_literalContext => visitArray_literal(array_literal)
      case pair_literal: V10WdlParser.Pair_literalContext   => visitPair_literal(pair_literal)
      case map_literal: V10WdlParser.Map_literalContext     => visitMap_literal(map_literal)
      case obj_literal: V10WdlParser.Object_literalContext  => visitObject_literal(obj_literal)
      case negate: V10WdlParser.NegateContext               => visitNegate(negate)
      case unirarysigned: V10WdlParser.UnirarysignedContext => visitUnirarysigned(unirarysigned)
      case at: V10WdlParser.AtContext                       => visitAt(at)
      case ifthenelse: V10WdlParser.IfthenelseContext       => visitIfthenelse(ifthenelse)
      case apply: V10WdlParser.ApplyContext                 => visitApply(apply)
      case left_name: V10WdlParser.Left_nameContext         => visitLeft_name(left_name)
      case get_name: V10WdlParser.Get_nameContext           => visitGet_name(get_name)
//      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /*
unbound_decls
	: wdl_type Identifier
	;
   */
  override def visitUnbound_decls(ctx: V10WdlParser.Unbound_declsContext): Declaration = {
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText
    Declaration(name, wdlType, None, getSourceText(ctx))
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: V10WdlParser.Bound_declsContext): Declaration = {
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText
    if (ctx.expr() == null)
      return Declaration(name, wdlType, None, getSourceText(ctx))
    val expr: Expr = visitExpr(ctx.expr())
    Declaration(name, wdlType, Some(expr), getSourceText(ctx))
  }

  /*
any_decls
	: unbound_decls
	| bound_decls
	;
   */
  override def visitAny_decls(ctx: V10WdlParser.Any_declsContext): Declaration = {
    if (ctx.unbound_decls() != null)
      return visitUnbound_decls(ctx.unbound_decls())
    if (ctx.bound_decls() != null)
      return visitBound_decls(ctx.bound_decls())
    throw new Exception("sanity")
  }

  /* meta_kv
   : Identifier COLON expr
   ; */
  override def visitMeta_kv(ctx: V10WdlParser.Meta_kvContext): MetaKV = {
    val id = ctx.Identifier().getText
    val expr = visitExpr(ctx.expr)
    MetaKV(id, expr, getSourceText(ctx))
  }

  //  PARAMETERMETA LBRACE meta_kv* RBRACE #parameter_meta
  override def visitParameter_meta(
      ctx: V10WdlParser.Parameter_metaContext
  ): ParameterMetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    ParameterMetaSection(kvs, getSourceText(ctx))
  }

  //  META LBRACE meta_kv* RBRACE #meta
  override def visitMeta(ctx: V10WdlParser.MetaContext): MetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    MetaSection(kvs, getSourceText(ctx))
  }

  /* task_runtime_kv
 : Identifier COLON expr
 ; */
  override def visitTask_runtime_kv(ctx: V10WdlParser.Task_runtime_kvContext): RuntimeKV = {
    val id: String = ctx.Identifier.getText
    val expr: Expr = visitExpr(ctx.expr())
    RuntimeKV(id, expr, getSourceText(ctx))
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: V10WdlParser.Task_runtimeContext): RuntimeSection = {
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitTask_runtime_kv(x))
      .toVector
    RuntimeSection(kvs, getSourceText(ctx))
  }

  /*
task_input
	: INPUT LBRACE (any_decls)* RBRACE
	;
   */
  override def visitTask_input(ctx: V10WdlParser.Task_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, getSourceText(ctx))
  }

  /* task_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	; */
  override def visitTask_output(ctx: V10WdlParser.Task_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, getSourceText(ctx))
  }

  /* task_command_string_part
    : CommandStringPart*
    ; */
  override def visitTask_command_string_part(
      ctx: V10WdlParser.Task_command_string_partContext
  ): ExprString = {
    val text: String = ctx
      .CommandStringPart()
      .asScala
      .map(x => x.getText)
      .mkString("")
    ExprString(text, getSourceText(ctx))
  }

  /* task_command_expr_part
    : StringCommandStart  (expression_placeholder_option)* expr RBRACE
    ; */
  override def visitTask_command_expr_part(
      ctx: V10WdlParser.Task_command_expr_partContext
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
      ctx: V10WdlParser.Task_command_expr_with_stringContext
  ): ExprCompoundString = {
    val exprPart: Expr = visitTask_command_expr_part(ctx.task_command_expr_part())
    val stringPart: Expr = visitTask_command_string_part(
        ctx.task_command_string_part()
    )
    ExprCompoundString(Vector(exprPart, stringPart), getSourceText(ctx))
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: V10WdlParser.Task_commandContext): CommandSection = {
    val start: Expr = visitTask_command_string_part(ctx.task_command_string_part())
    val parts: Vector[Expr] = ctx
      .task_command_expr_with_string()
      .asScala
      .map(x => visitTask_command_expr_with_string(x))
      .toVector

    val allParts: Vector[Expr] = start +: parts

    // discard empty strings, and flatten compound vectors of strings
    val cleanedParts = allParts.flatMap {
      case ExprString(x, _) if x.isEmpty => Vector.empty
      case ExprCompoundString(v, _)      => v
      case other                         => Vector(other)
    }

    // TODO: do the above until reaching a fixed point

    CommandSection(cleanedParts, getSourceText(ctx))
  }

  // A that should appear zero or once. Make sure this is the case.
  private def atMostOneSection[T](sections: Vector[T],
                                  sectionName: String,
                                  ctx: ParserRuleContext): Option[T] = {
    sections.size match {
      case 0 => None
      case 1 => Some(sections.head)
      case n =>
        throw makeWdlException(
            s"section ${sectionName} appears ${n} times, it cannot appear more than once",
            ctx
        )
    }
  }

  // A section that must appear exactly once
  private def exactlyOneSection[T](sections: Vector[T],
                                   sectionName: String,
                                   ctx: ParserRuleContext): T = {
    sections.size match {
      case 1 => sections.head
      case n =>
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
      case MetaKV(k, _, _) =>
        if (!(ioVarNames contains k))
          throw makeWdlException(s"parameter ${k} does not appear in the input or output sections",
                                 ctx)
    }
  }

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: V10WdlParser.TaskContext): Task = {
    val name = ctx.Identifier().getText
    val elems = ctx.task_element().asScala.map(visitTask_element).toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: InputSection => x
    }, "input", ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: OutputSection => x
    }, "output", ctx)
    val command: CommandSection = exactlyOneSection(elems.collect {
      case x: CommandSection => x
    }, "command", ctx)
    val decls: Vector[Declaration] = elems.collect {
      case x: Declaration => x
    }
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: MetaSection => x
    }, "meta", ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: ParameterMetaSection => x
    }, "parameter_meta", ctx)
    val runtime: Option[RuntimeSection] = atMostOneSection(elems.collect {
      case x: RuntimeSection => x
    }, "runtime", ctx)

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Task(name,
         input = input,
         output = output,
         command = command,
         declarations = decls,
         meta = meta,
         parameterMeta = parameterMeta,
         runtime = runtime,
         text = getSourceText(ctx))
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: V10WdlParser.Import_aliasContext): ImportAlias = {
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    ImportAlias(ids(0), ids(1), getSourceText(ctx))
  }

  /*
import_as
    : AS Identifier
    ;

 import_doc
	: IMPORT string import_as? (import_alias)*
	;
   */
  override def visitImport_doc(ctx: V10WdlParser.Import_docContext): ImportDoc = {
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
    ImportDoc(name, aliases, URL(url), getSourceText(ctx))
  }

  /* call_alias
	: AS Identifier
	; */
  override def visitCall_alias(ctx: V10WdlParser.Call_aliasContext): CallAlias = {
    CallAlias(ctx.Identifier().getText, getSourceText(ctx))
  }

  /* call_input
	: Identifier EQUAL expr
	; */
  override def visitCall_input(ctx: V10WdlParser.Call_inputContext): CallInput = {
    val expr = visitExpr(ctx.expr())
    CallInput(ctx.Identifier().getText, expr, getSourceText(ctx))
  }

  /* call_inputs
	: INPUT COLON (call_input (COMMA call_input)*)
	; */
  override def visitCall_inputs(ctx: V10WdlParser.Call_inputsContext): CallInputs = {
    val inputs: Map[String, Expr] = ctx
      .call_input()
      .asScala
      .map { x =>
        val inp = visitCall_input(x)
        inp.name -> inp.expr
      }
      .toMap
    CallInputs(inputs, getSourceText(ctx))
  }

  /* call_body
	: LBRACE call_inputs? RBRACE
	; */
  override def visitCall_body(ctx: V10WdlParser.Call_bodyContext): CallInputs = {
    if (ctx.call_inputs() == null)
      CallInputs(Map.empty, getSourceText(ctx))
    else
      visitCall_inputs(ctx.call_inputs())
  }

  /* call
	: CALL Identifier call_alias?  call_body?
	; */
  override def visitCall(ctx: V10WdlParser.CallContext): Call = {
    val name = ctx.call_name().getText

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

    Call(name, alias, inputs, getSourceText(ctx))
  }

  /*
scatter
	: SCATTER LPAREN Identifier In expr RPAREN LBRACE inner_workflow_element* RBRACE
 ; */
  override def visitScatter(ctx: V10WdlParser.ScatterContext): Scatter = {
    val id = ctx.Identifier.getText
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Scatter(id, expr, body, getSourceText(ctx))
  }

  /* conditional
	: IF LPAREN expr RPAREN LBRACE inner_workflow_element* RBRACE
	; */
  override def visitConditional(ctx: V10WdlParser.ConditionalContext): Conditional = {
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Conditional(expr, body, getSourceText(ctx))
  }

  /* workflow_input
	: INPUT LBRACE (any_decls)* RBRACE
	; */
  override def visitWorkflow_input(ctx: V10WdlParser.Workflow_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, getSourceText(ctx))
  }

  /* workflow_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	;
   */
  override def visitWorkflow_output(ctx: V10WdlParser.Workflow_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, getSourceText(ctx))
  }

  /* inner_workflow_element
	: bound_decls
	| call
	| scatter
	| conditional
	; */
  override def visitInner_workflow_element(
      ctx: V10WdlParser.Inner_workflow_elementContext
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
  override def visitWorkflow(ctx: V10WdlParser.WorkflowContext): Workflow = {
    val name = ctx.Identifier().getText
    val elems: Vector[V10WdlParser.Workflow_elementContext] =
      ctx.workflow_element().asScala.toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: V10WdlParser.InputContext =>
        visitWorkflow_input(x.workflow_input())
    }, "input", ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: V10WdlParser.OutputContext =>
        visitWorkflow_output(x.workflow_output())
    }, "output", ctx)
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: V10WdlParser.Meta_elementContext =>
        visitMeta(x.meta())
    }, "meta", ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: V10WdlParser.Parameter_meta_elementContext =>
        visitParameter_meta(x.parameter_meta())
    }, "parameter_meta", ctx)
    val wfElems: Vector[WorkflowElement] = elems.collect {
      case x: V10WdlParser.Inner_elementContext =>
        visitInner_workflow_element(x.inner_workflow_element())
    }

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Workflow(name, input, output, meta, parameterMeta, wfElems, getSourceText(ctx))
  }

  /*
document_element
	: import_doc
	| struct
	| task
	;
   */
  override def visitDocument_element(ctx: V10WdlParser.Document_elementContext): DocumentElement = {
    visitChildren(ctx).asInstanceOf[DocumentElement]
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: V10WdlParser.VersionContext): Version = {
    if (ctx.RELEASE_VERSION() == null)
      throw new Exception("version not specified")
    val value = ctx.RELEASE_VERSION().getText
    Version(value, getSourceText(ctx))
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: V10WdlParser.DocumentContext): Document = {
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

    Document(WdlVersion.fromName(version.value), elems, workflow, getSourceText(ctx))
  }

  def apply(): Document = {
    visitDocument(grammar.parser.document)
  }
}