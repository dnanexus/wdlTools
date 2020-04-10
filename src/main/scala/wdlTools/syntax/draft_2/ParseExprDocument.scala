package wdlTools.syntax.draft_2

import collection.JavaConverters._
import org.antlr.v4.runtime.{CharStream, CommonTokenStream, ParserRuleContext}
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.draft_2.{
  WdlDraft2ExprParser,
  WdlDraft2ExprParserBaseVisitor,
  WdlDraft2Lexer
}
import wdlTools.syntax.Antlr4Util.{Grammar, GrammarFactory}
import wdlTools.syntax.{AbstractSyntax, TextSource}
import wdlTools.syntax.draft_2.ConcreteSyntax._
import wdlTools.util.Options

object ParseExprDocument {
  case class Draft2ExprGrammarFactory(opts: Options)
      extends GrammarFactory[WdlDraft2Lexer, WdlDraft2ExprParser](opts) {
    override def createLexer(charStream: CharStream): WdlDraft2Lexer = {
      new WdlDraft2Lexer(charStream)
    }

    override def createParser(tokenStream: CommonTokenStream): WdlDraft2ExprParser = {
      new WdlDraft2ExprParser(tokenStream)
    }
  }
}

case class ParseExprDocument(grammar: Grammar[WdlDraft2Lexer, WdlDraft2ExprParser], opts: Options)
    extends WdlDraft2ExprParserBaseVisitor[Element] {
  protected def makeWdlException(msg: String, ctx: ParserRuleContext): RuntimeException = {
    grammar.makeWdlException(msg, ctx)
  }

  protected def getSourceText(ctx: ParserRuleContext): TextSource = {
    grammar.getSourceText(ctx, None)
  }

  protected def getSourceText(symbol: TerminalNode): TextSource = {
    grammar.getSourceText(symbol, None)
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: WdlDraft2ExprParser.StringContext): Expr = {
    ExprString(ctx.string_part().getText, getSourceText(ctx.string_part()))
  }

  override def visitNumber(ctx: WdlDraft2ExprParser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText.toInt, getSourceText(ctx))
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble, getSourceText(ctx))
    }
    throw makeWdlException(s"Not an integer nor a float ${ctx.getText}", ctx)
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: WdlDraft2ExprParser.Primitive_literalContext): Expr = {
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

  override def visitLor(ctx: WdlDraft2ExprParser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, getSourceText(ctx))
  }

  override def visitLand(ctx: WdlDraft2ExprParser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, getSourceText(ctx))
  }

  override def visitEqeq(ctx: WdlDraft2ExprParser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, getSourceText(ctx))
  }
  override def visitLt(ctx: WdlDraft2ExprParser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, getSourceText(ctx))
  }

  override def visitGte(ctx: WdlDraft2ExprParser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, getSourceText(ctx))
  }

  override def visitNeq(ctx: WdlDraft2ExprParser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, getSourceText(ctx))
  }

  override def visitLte(ctx: WdlDraft2ExprParser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, getSourceText(ctx))
  }

  override def visitGt(ctx: WdlDraft2ExprParser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, getSourceText(ctx))
  }

  override def visitAdd(ctx: WdlDraft2ExprParser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, getSourceText(ctx))
  }

  override def visitSub(ctx: WdlDraft2ExprParser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, getSourceText(ctx))
  }

  override def visitMod(ctx: WdlDraft2ExprParser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, getSourceText(ctx))
  }

  override def visitMul(ctx: WdlDraft2ExprParser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, getSourceText(ctx))
  }

  override def visitDivide(ctx: WdlDraft2ExprParser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, getSourceText(ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: WdlDraft2ExprParser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: WdlDraft2ExprParser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, getSourceText(ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: WdlDraft2ExprParser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, getSourceText(ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: WdlDraft2ExprParser.Map_literalContext): Expr = {
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
  override def visitObject_literal(ctx: WdlDraft2ExprParser.Object_literalContext): Expr = {
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
  override def visitNegate(ctx: WdlDraft2ExprParser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, getSourceText(ctx))
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: WdlDraft2ExprParser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr, getSourceText(ctx))
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr, getSourceText(ctx))
    else
      throw makeWdlException("sanity", ctx)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlDraft2ExprParser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, getSourceText(ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlDraft2ExprParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, getSourceText(ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: WdlDraft2ExprParser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), getSourceText(ctx))
  }

  override def visitLeft_name(ctx: WdlDraft2ExprParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id, getSourceText(ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlDraft2ExprParser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, getSourceText(ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: WdlDraft2ExprParser.Expr_infix0Context): Expr = {
    ctx match {
      case lor: WdlDraft2ExprParser.LorContext       => visitLor(lor)
      case infix1: WdlDraft2ExprParser.Infix1Context => visitInfix1(infix1).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: WdlDraft2ExprParser.Expr_infix1Context): Expr = {
    ctx match {
      case land: WdlDraft2ExprParser.LandContext     => visitLand(land)
      case infix2: WdlDraft2ExprParser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
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

  private def visitExpr_infix2(ctx: WdlDraft2ExprParser.Expr_infix2Context): Expr = {
    ctx match {
      case eqeq: WdlDraft2ExprParser.EqeqContext     => visitEqeq(eqeq)
      case neq: WdlDraft2ExprParser.NeqContext       => visitNeq(neq)
      case lte: WdlDraft2ExprParser.LteContext       => visitLte(lte)
      case gte: WdlDraft2ExprParser.GteContext       => visitGte(gte)
      case lt: WdlDraft2ExprParser.LtContext         => visitLt(lt)
      case gt: WdlDraft2ExprParser.GtContext         => visitGt(gt)
      case infix3: WdlDraft2ExprParser.Infix3Context => visitInfix3(infix3).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: WdlDraft2ExprParser.Expr_infix3Context): Expr = {
    ctx match {
      case add: WdlDraft2ExprParser.AddContext       => visitAdd(add)
      case sub: WdlDraft2ExprParser.SubContext       => visitSub(sub)
      case infix4: WdlDraft2ExprParser.Infix4Context => visitInfix4(infix4).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: WdlDraft2ExprParser.Expr_infix4Context): Expr = {
    ctx match {
      case mul: WdlDraft2ExprParser.MulContext       => visitMul(mul)
      case divide: WdlDraft2ExprParser.DivideContext => visitDivide(divide)
      case mod: WdlDraft2ExprParser.ModContext       => visitMod(mod)
      case infix5: WdlDraft2ExprParser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */
  override def visitExpr_infix5(ctx: WdlDraft2ExprParser.Expr_infix5Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: WdlDraft2ExprParser.ExprContext): Expr = {
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
  private def visitExpr_core(ctx: WdlDraft2ExprParser.Expr_coreContext): Expr = {
    ctx match {
      case group: WdlDraft2ExprParser.Expression_groupContext => visitExpression_group(group)
      case primitives: WdlDraft2ExprParser.PrimitivesContext =>
        visitChildren(primitives).asInstanceOf[Expr]
      case array_literal: WdlDraft2ExprParser.Array_literalContext =>
        visitArray_literal(array_literal)
      case pair_literal: WdlDraft2ExprParser.Pair_literalContext => visitPair_literal(pair_literal)
      case map_literal: WdlDraft2ExprParser.Map_literalContext   => visitMap_literal(map_literal)
      case obj_literal: WdlDraft2ExprParser.Object_literalContext =>
        visitObject_literal(obj_literal)
      case negate: WdlDraft2ExprParser.NegateContext => visitNegate(negate)
      case unirarysigned: WdlDraft2ExprParser.UnirarysignedContext =>
        visitUnirarysigned(unirarysigned)
      case at: WdlDraft2ExprParser.AtContext                 => visitAt(at)
      case ifthenelse: WdlDraft2ExprParser.IfthenelseContext => visitIfthenelse(ifthenelse)
      case apply: WdlDraft2ExprParser.ApplyContext           => visitApply(apply)
      case left_name: WdlDraft2ExprParser.Left_nameContext   => visitLeft_name(left_name)
      case get_name: WdlDraft2ExprParser.Get_nameContext     => visitGet_name(get_name)
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: WdlDraft2ExprParser.DocumentContext): Expr = {
    visitExpr(ctx.expr())
  }

  def apply(): AbstractSyntax.Expr = {
    val concreteExpr = visitDocument(grammar.parser.document)
    Translators.translateExpr(concreteExpr)
  }
}