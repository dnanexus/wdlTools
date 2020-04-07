package wdlTools.syntax.draft_2

import collection.JavaConverters._
import org.antlr.v4.runtime.{CharStream, CommonTokenStream, ParserRuleContext}
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.draft_2.{
  Draft2WdlExprParser,
  Draft2WdlExprParserBaseVisitor,
  Draft2WdlLexer
}
import wdlTools.syntax.Antlr4Util.{Grammar, GrammarFactory}
import wdlTools.syntax.{AbstractSyntax, TextSource}
import wdlTools.syntax.draft_2.ConcreteSyntax._
import wdlTools.util.Options

object ParseExprDocument {
  case class Draft2ExprGrammarFactory(opts: Options)
      extends GrammarFactory[Draft2WdlLexer, Draft2WdlExprParser](opts) {
    override def createLexer(charStream: CharStream): Draft2WdlLexer = {
      new Draft2WdlLexer(charStream)
    }

    override def createParser(tokenStream: CommonTokenStream): Draft2WdlExprParser = {
      new Draft2WdlExprParser(tokenStream)
    }
  }
}

case class ParseExprDocument(grammar: Grammar[Draft2WdlLexer, Draft2WdlExprParser], opts: Options)
    extends Draft2WdlExprParserBaseVisitor[Element] {
  protected def makeWdlException(msg: String, ctx: ParserRuleContext): RuntimeException = {
    grammar.makeWdlException(msg, ctx)
  }

  protected def getSourceText(ctx: ParserRuleContext): TextSource = {
    grammar.getSourceText(ctx)
  }

  protected def getSourceText(symbol: TerminalNode): TextSource = {
    grammar.getSourceText(symbol)
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: Draft2WdlExprParser.StringContext): Expr = {
    ExprString(ctx.string_part().getText, getSourceText(ctx.string_part()))
  }

  override def visitNumber(ctx: Draft2WdlExprParser.NumberContext): Expr = {
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
  override def visitPrimitive_literal(ctx: Draft2WdlExprParser.Primitive_literalContext): Expr = {
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

  override def visitLor(ctx: Draft2WdlExprParser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, getSourceText(ctx))
  }

  override def visitLand(ctx: Draft2WdlExprParser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, getSourceText(ctx))
  }

  override def visitEqeq(ctx: Draft2WdlExprParser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, getSourceText(ctx))
  }
  override def visitLt(ctx: Draft2WdlExprParser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, getSourceText(ctx))
  }

  override def visitGte(ctx: Draft2WdlExprParser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, getSourceText(ctx))
  }

  override def visitNeq(ctx: Draft2WdlExprParser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, getSourceText(ctx))
  }

  override def visitLte(ctx: Draft2WdlExprParser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, getSourceText(ctx))
  }

  override def visitGt(ctx: Draft2WdlExprParser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, getSourceText(ctx))
  }

  override def visitAdd(ctx: Draft2WdlExprParser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, getSourceText(ctx))
  }

  override def visitSub(ctx: Draft2WdlExprParser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, getSourceText(ctx))
  }

  override def visitMod(ctx: Draft2WdlExprParser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, getSourceText(ctx))
  }

  override def visitMul(ctx: Draft2WdlExprParser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, getSourceText(ctx))
  }

  override def visitDivide(ctx: Draft2WdlExprParser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, getSourceText(ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: Draft2WdlExprParser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: Draft2WdlExprParser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, getSourceText(ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: Draft2WdlExprParser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, getSourceText(ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: Draft2WdlExprParser.Map_literalContext): Expr = {
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
  override def visitObject_literal(ctx: Draft2WdlExprParser.Object_literalContext): Expr = {
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
  override def visitNegate(ctx: Draft2WdlExprParser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, getSourceText(ctx))
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: Draft2WdlExprParser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr, getSourceText(ctx))
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr, getSourceText(ctx))
    else
      throw makeWdlException("sanity", ctx)
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: Draft2WdlExprParser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, getSourceText(ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: Draft2WdlExprParser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, getSourceText(ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: Draft2WdlExprParser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), getSourceText(ctx))
  }

  override def visitLeft_name(ctx: Draft2WdlExprParser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id, getSourceText(ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: Draft2WdlExprParser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, getSourceText(ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: Draft2WdlExprParser.Expr_infix0Context): Expr = {
    ctx match {
      case lor: Draft2WdlExprParser.LorContext       => visitLor(lor)
      case infix1: Draft2WdlExprParser.Infix1Context => visitInfix1(infix1).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: Draft2WdlExprParser.Expr_infix1Context): Expr = {
    ctx match {
      case land: Draft2WdlExprParser.LandContext     => visitLand(land)
      case infix2: Draft2WdlExprParser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
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

  private def visitExpr_infix2(ctx: Draft2WdlExprParser.Expr_infix2Context): Expr = {
    ctx match {
      case eqeq: Draft2WdlExprParser.EqeqContext     => visitEqeq(eqeq)
      case neq: Draft2WdlExprParser.NeqContext       => visitNeq(neq)
      case lte: Draft2WdlExprParser.LteContext       => visitLte(lte)
      case gte: Draft2WdlExprParser.GteContext       => visitGte(gte)
      case lt: Draft2WdlExprParser.LtContext         => visitLt(lt)
      case gt: Draft2WdlExprParser.GtContext         => visitGt(gt)
      case infix3: Draft2WdlExprParser.Infix3Context => visitInfix3(infix3).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: Draft2WdlExprParser.Expr_infix3Context): Expr = {
    ctx match {
      case add: Draft2WdlExprParser.AddContext       => visitAdd(add)
      case sub: Draft2WdlExprParser.SubContext       => visitSub(sub)
      case infix4: Draft2WdlExprParser.Infix4Context => visitInfix4(infix4).asInstanceOf[Expr]
      case _                                         => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: Draft2WdlExprParser.Expr_infix4Context): Expr = {
    ctx match {
      case mul: Draft2WdlExprParser.MulContext       => visitMul(mul)
      case divide: Draft2WdlExprParser.DivideContext => visitDivide(divide)
      case mod: Draft2WdlExprParser.ModContext       => visitMod(mod)
      case infix5: Draft2WdlExprParser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */
  override def visitExpr_infix5(ctx: Draft2WdlExprParser.Expr_infix5Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: Draft2WdlExprParser.ExprContext): Expr = {
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
  private def visitExpr_core(ctx: Draft2WdlExprParser.Expr_coreContext): Expr = {
    ctx match {
      case group: Draft2WdlExprParser.Expression_groupContext => visitExpression_group(group)
      case primitives: Draft2WdlExprParser.PrimitivesContext =>
        visitChildren(primitives).asInstanceOf[Expr]
      case array_literal: Draft2WdlExprParser.Array_literalContext =>
        visitArray_literal(array_literal)
      case pair_literal: Draft2WdlExprParser.Pair_literalContext => visitPair_literal(pair_literal)
      case map_literal: Draft2WdlExprParser.Map_literalContext   => visitMap_literal(map_literal)
      case obj_literal: Draft2WdlExprParser.Object_literalContext =>
        visitObject_literal(obj_literal)
      case negate: Draft2WdlExprParser.NegateContext => visitNegate(negate)
      case unirarysigned: Draft2WdlExprParser.UnirarysignedContext =>
        visitUnirarysigned(unirarysigned)
      case at: Draft2WdlExprParser.AtContext                 => visitAt(at)
      case ifthenelse: Draft2WdlExprParser.IfthenelseContext => visitIfthenelse(ifthenelse)
      case apply: Draft2WdlExprParser.ApplyContext           => visitApply(apply)
      case left_name: Draft2WdlExprParser.Left_nameContext   => visitLeft_name(left_name)
      case get_name: Draft2WdlExprParser.Get_nameContext     => visitGet_name(get_name)
      //      case _ => visitChildren(ctx).asInstanceOf[Expr]
    }
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: Draft2WdlExprParser.DocumentContext): Expr = {
    visitExpr(ctx.expr())
  }

  def apply(): AbstractSyntax.Expr = {
    val concreteExpr = visitDocument(grammar.parser.document)
    Translators.translateExpr(concreteExpr)
  }
}
