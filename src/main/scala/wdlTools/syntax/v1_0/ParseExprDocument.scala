package wdlTools.syntax.v1_0

import scala.collection.JavaConverters._
import org.antlr.v4.runtime.{CharStream, CommonTokenStream, ParserRuleContext}
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.v1_0.{V10WdlExprParser, V10WdlExprParserBaseVisitor, V10WdlLexer}
import wdlTools.syntax.Antlr4Util.{Grammar, GrammarFactory}
import wdlTools.syntax.{AbstractSyntax, TextSource}
import wdlTools.syntax.v1_0.ConcreteSyntax._
import wdlTools.util.Options

object ParseExprDocument {
  case class V1_0ExprGrammarFactory(opts: Options)
      extends GrammarFactory[V10WdlLexer, V10WdlExprParser](opts) {
    override def createLexer(charStream: CharStream): V10WdlLexer = {
      new V10WdlLexer(charStream)
    }

    override def createParser(tokenStream: CommonTokenStream): V10WdlExprParser = {
      new V10WdlExprParser(tokenStream)
    }
  }
}

case class ParseExprDocument(grammar: Grammar[V10WdlLexer, V10WdlExprParser], opts: Options)
    extends V10WdlExprParserBaseVisitor[Element] {
  protected def makeWdlException(msg: String, ctx: ParserRuleContext): RuntimeException = {
    grammar.makeWdlException(msg, ctx)
  }

  protected def getSourceText(ctx: ParserRuleContext): TextSource = {
    grammar.getSourceText(ctx)
  }

  protected def getSourceText(symbol: TerminalNode): TextSource = {
    grammar.getSourceText(symbol)
  }

  // EXPRESSIONS

  override def visitNumber(ctx: V10WdlExprParser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText.toInt, getSourceText(ctx))
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble, getSourceText(ctx))
    }
    throw makeWdlException(s"Not an integer nor a float ${ctx.getText}", ctx)
  }

  /* string_part
  : StringPart*
  ; */
  override def visitString_part(ctx: V10WdlExprParser.String_partContext): ExprCompoundString = {
    val parts: Vector[Expr] = ctx
      .StringPart()
      .asScala
      .map(x => ExprString(x.getText, getSourceText(x)))
      .toVector
    ExprCompoundString(parts, getSourceText(ctx))
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: V10WdlExprParser.StringContext): Expr = {
    ExprString(ctx.string_part().getText, getSourceText(ctx.string_part()))
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: V10WdlExprParser.Primitive_literalContext): Expr = {
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

  override def visitLor(ctx: V10WdlExprParser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, getSourceText(ctx))
  }

  override def visitLand(ctx: V10WdlExprParser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, getSourceText(ctx))
  }

  override def visitEqeq(ctx: V10WdlExprParser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, getSourceText(ctx))
  }
  override def visitLt(ctx: V10WdlExprParser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, getSourceText(ctx))
  }

  override def visitGte(ctx: V10WdlExprParser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, getSourceText(ctx))
  }

  override def visitNeq(ctx: V10WdlExprParser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, getSourceText(ctx))
  }

  override def visitLte(ctx: V10WdlExprParser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, getSourceText(ctx))
  }

  override def visitGt(ctx: V10WdlExprParser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, getSourceText(ctx))
  }

  override def visitAdd(ctx: V10WdlExprParser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, getSourceText(ctx))
  }

  override def visitSub(ctx: V10WdlExprParser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, getSourceText(ctx))
  }

  override def visitMod(ctx: V10WdlExprParser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, getSourceText(ctx))
  }

  override def visitMul(ctx: V10WdlExprParser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, getSourceText(ctx))
  }

  override def visitDivide(ctx: V10WdlExprParser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, getSourceText(ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: V10WdlExprParser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: V10WdlExprParser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, getSourceText(ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: V10WdlExprParser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, getSourceText(ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: V10WdlExprParser.Map_literalContext): Expr = {
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
  override def visitObject_literal(ctx: V10WdlExprParser.Object_literalContext): Expr = {
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
  override def visitNegate(ctx: V10WdlExprParser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, getSourceText(ctx))
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: V10WdlExprParser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr, getSourceText(ctx))
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr, getSourceText(ctx))
    else
      throw makeWdlException("sanity", ctx)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: V10WdlExprParser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, getSourceText(ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: V10WdlExprParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, getSourceText(ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: V10WdlExprParser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), getSourceText(ctx))
  }

  override def visitLeft_name(ctx: V10WdlExprParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id, getSourceText(ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: V10WdlExprParser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, getSourceText(ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: V10WdlExprParser.Expr_infix0Context): Expr = {
    ctx match {
      case lor: V10WdlExprParser.LorContext       => visitLor(lor)
      case infix1: V10WdlExprParser.Infix1Context => visitInfix1(infix1).asInstanceOf[Expr]
      case _                                      => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: V10WdlExprParser.Expr_infix1Context): Expr = {
    ctx match {
      case land: V10WdlExprParser.LandContext     => visitLand(land)
      case infix2: V10WdlExprParser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
      case _                                      => visitChildren(ctx).asInstanceOf[Expr]
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

  private def visitExpr_infix2(ctx: V10WdlExprParser.Expr_infix2Context): Expr = {
    ctx match {
      case eqeq: V10WdlExprParser.EqeqContext     => visitEqeq(eqeq)
      case neq: V10WdlExprParser.NeqContext       => visitNeq(neq)
      case lte: V10WdlExprParser.LteContext       => visitLte(lte)
      case gte: V10WdlExprParser.GteContext       => visitGte(gte)
      case lt: V10WdlExprParser.LtContext         => visitLt(lt)
      case gt: V10WdlExprParser.GtContext         => visitGt(gt)
      case infix3: V10WdlExprParser.Infix3Context => visitInfix3(infix3).asInstanceOf[Expr]
      case _                                      => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: V10WdlExprParser.Expr_infix3Context): Expr = {
    ctx match {
      case add: V10WdlExprParser.AddContext       => visitAdd(add)
      case sub: V10WdlExprParser.SubContext       => visitSub(sub)
      case infix4: V10WdlExprParser.Infix4Context => visitInfix4(infix4).asInstanceOf[Expr]
      case _                                      => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: V10WdlExprParser.Expr_infix4Context): Expr = {
    ctx match {
      case mul: V10WdlExprParser.MulContext       => visitMul(mul)
      case divide: V10WdlExprParser.DivideContext => visitDivide(divide)
      case mod: V10WdlExprParser.ModContext       => visitMod(mod)
      case infix5: V10WdlExprParser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */

  override def visitExpr_infix5(ctx: V10WdlExprParser.Expr_infix5Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: V10WdlExprParser.ExprContext): Expr = {
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
  private def visitExpr_core(ctx: V10WdlExprParser.Expr_coreContext): Expr = {
    ctx match {
      case group: V10WdlExprParser.Expression_groupContext => visitExpression_group(group)
      case primitives: V10WdlExprParser.PrimitivesContext =>
        visitChildren(primitives).asInstanceOf[Expr]
      case array_literal: V10WdlExprParser.Array_literalContext => visitArray_literal(array_literal)
      case pair_literal: V10WdlExprParser.Pair_literalContext   => visitPair_literal(pair_literal)
      case map_literal: V10WdlExprParser.Map_literalContext     => visitMap_literal(map_literal)
      case obj_literal: V10WdlExprParser.Object_literalContext  => visitObject_literal(obj_literal)
      case negate: V10WdlExprParser.NegateContext               => visitNegate(negate)
      case unirarysigned: V10WdlExprParser.UnirarysignedContext => visitUnirarysigned(unirarysigned)
      case at: V10WdlExprParser.AtContext                       => visitAt(at)
      case ifthenelse: V10WdlExprParser.IfthenelseContext       => visitIfthenelse(ifthenelse)
      case apply: V10WdlExprParser.ApplyContext                 => visitApply(apply)
      case left_name: V10WdlExprParser.Left_nameContext         => visitLeft_name(left_name)
      case get_name: V10WdlExprParser.Get_nameContext           => visitGet_name(get_name)
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: V10WdlExprParser.DocumentContext): Expr = {
    visitExpr(ctx.expr())
  }

  def apply(): AbstractSyntax.Expr = {
    val concreteExpr = visitDocument(grammar.parser.document)
    Translators.translateExpr(concreteExpr)
  }
}
