package wdlTools.syntax.v2

// Parse one document. Do not follow imports.

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.v2.{WdlV2Parser, WdlV2ParserBaseVisitor}
import wdlTools.syntax.Antlr4Util.getSourceLocation
import wdlTools.syntax.v2.ConcreteSyntax._
import wdlTools.syntax.{CommentMap, SourceLocation, SyntaxException, WdlVersion}
import dx.util.Logger

import scala.jdk.CollectionConverters._

case class ParseTop(grammar: WdlV2Grammar) extends WdlV2ParserBaseVisitor[Element] {

  private def getIdentifierText(identifier: TerminalNode, ctx: ParserRuleContext): String = {
    if (identifier == null) {
      throw new SyntaxException("missing identifier", getSourceLocation(grammar.docSource, ctx))
    }
    identifier.getText
  }

  /*
struct
	: STRUCT Identifier LBRACE (unbound_decls)* RBRACE
	;
   */
  override def visitStruct(ctx: WdlV2Parser.StructContext): TypeStruct = {
    val sName = getIdentifierText(ctx.Identifier(), ctx)
    val members: Vector[StructMember] = ctx
      .unbound_decls()
      .asScala
      .map { x =>
        val decl = visitUnbound_decls(x)
        StructMember(decl.name, decl.wdlType, decl.loc)
      }
      .toVector

    // check that each field appears once
    members.foldLeft(Set.empty[String]) {
      case (names, member) if names.contains(member.name) =>
        throw new SyntaxException(s"struct ${sName} has field ${member.name} defined twice",
                                  getSourceLocation(grammar.docSource, ctx))
      case (names, member) => names + member.name
    }

    TypeStruct(sName, members, getSourceLocation(grammar.docSource, ctx))
  }

  /*
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: WdlV2Parser.Map_typeContext): Type = {
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt, getSourceLocation(grammar.docSource, ctx))
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: WdlV2Parser.Array_typeContext): Type = {
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = ctx.PLUS() != null
    TypeArray(t, nonEmpty, getSourceLocation(grammar.docSource, ctx))
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: WdlV2Parser.Pair_typeContext): Type = {
    val lt: Type = visitWdl_type(ctx.wdl_type(0))
    val rt: Type = visitWdl_type(ctx.wdl_type(1))
    TypePair(lt, rt, getSourceLocation(grammar.docSource, ctx))
  }

  /*
type_base
	: array_type
	| map_type
	| pair_type
	| (STRING | FILE | DIRECTORY |  BOOLEAN | INT | FLOAT | Identifier)
	;
   */
  override def visitType_base(ctx: WdlV2Parser.Type_baseContext): Type = {
    if (ctx.array_type() != null)
      return visitArray_type(ctx.array_type())
    if (ctx.map_type() != null)
      return visitMap_type(ctx.map_type())
    if (ctx.pair_type() != null)
      return visitPair_type(ctx.pair_type())
    if (ctx.STRING() != null)
      return TypeString(getSourceLocation(grammar.docSource, ctx))
    if (ctx.FILE() != null)
      return TypeFile(getSourceLocation(grammar.docSource, ctx))
    if (ctx.DIRECTORY() != null)
      return TypeDirectory(getSourceLocation(grammar.docSource, ctx))
    if (ctx.BOOLEAN() != null)
      return TypeBoolean(getSourceLocation(grammar.docSource, ctx))
    if (ctx.INT() != null)
      return TypeInt(getSourceLocation(grammar.docSource, ctx))
    if (ctx.FLOAT() != null)
      return TypeFloat(getSourceLocation(grammar.docSource, ctx))
    if (ctx.Identifier() != null)
      return TypeIdentifier(ctx.getText, getSourceLocation(grammar.docSource, ctx))
    throw new SyntaxException("unrecgonized type", getSourceLocation(grammar.docSource, ctx))
  }

  /*
wdl_type
  : (type_base OPTIONAL | type_base)
  ;
   */
  override def visitWdl_type(ctx: WdlV2Parser.Wdl_typeContext): Type = {
    if (ctx.type_base == null)
      throw new SyntaxException("bad type", getSourceLocation(grammar.docSource, ctx))
    val t = visitType_base(ctx.type_base())
    if (ctx.OPTIONAL() != null) {
      TypeOptional(t, getSourceLocation(grammar.docSource, ctx))
    } else {
      t
    }
  }

  // EXPRESSIONS

  override def visitNumber(ctx: WdlV2Parser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText.toLong, getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble, getSourceLocation(grammar.docSource, ctx))
    }
    throw new SyntaxException(s"Not an integer nor a float ${ctx.getText}",
                              getSourceLocation(grammar.docSource, ctx))
  }

  /* string_part
  : StringPart*
  ; */
  override def visitString_part(ctx: WdlV2Parser.String_partContext): Expr = {
    ctx
      .StringPart()
      .asScala
      .map(x => ExprString(x.getText, getSourceLocation(grammar.docSource, x)))
      .filterNot(_.value.isEmpty)
      .toVector match {
      case Vector()  => ExprString("", getSourceLocation(grammar.docSource, ctx))
      case Vector(e) => e
      case parts     => ExprCompoundString(parts, getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* string_expr_with_string_part
  : string_expr_part string_part
  ; */
  override def visitString_expr_with_string_part(
      ctx: WdlV2Parser.String_expr_with_string_partContext
  ): Expr = {
    val exprPart = visitExpr(ctx.string_expr_part().expr())
    val stringPart = visitString_part(ctx.string_part())
    val loc = getSourceLocation(grammar.docSource, ctx)
    (exprPart, stringPart) match {
      case (e, ExprString(s, _)) if s.isEmpty => ExprCompoundString(Vector(e), loc)
      case (e, s)                             => ExprCompoundString(Vector(e, s), loc)
    }
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: WdlV2Parser.StringContext): Expr = {
    val stringPart =
      ExprString(ctx.string_part().getText, getSourceLocation(grammar.docSource, ctx.string_part()))
    val exprPart: Vector[Expr] = ctx
      .string_expr_with_string_part()
      .asScala
      .map(visitString_expr_with_string_part)
      .toVector
      .flatMap {
        case ExprCompoundString(v, _) => v
        case e                        => Vector(e)
      }
    (stringPart, exprPart) match {
      case (s: ExprString, Vector()) => s
      case (ExprString(s, _), parts) if s.isEmpty =>
        ExprCompoundString(parts, getSourceLocation(grammar.docSource, ctx))
      case (s, parts) =>
        ExprCompoundString(s +: parts, getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* primitive_literal
	: None
	| BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: WdlV2Parser.Primitive_literalContext): Expr = {
    if (ctx.NONELITERAL() != null) {
      return ExprNone(getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      return ExprBoolean(value, getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.number() != null) {
      return visitNumber(ctx.number())
    }
    if (ctx.string() != null) {
      return visitString(ctx.string())
    }
    if (ctx.Identifier() != null) {
      return ExprIdentifier(ctx.getText, getSourceLocation(grammar.docSource, ctx))
    }
    throw new SyntaxException("Not one of four supported variants of primitive_literal",
                              getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLor(ctx: WdlV2Parser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLand(ctx: WdlV2Parser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitEqeq(ctx: WdlV2Parser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }
  override def visitLt(ctx: WdlV2Parser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitGte(ctx: WdlV2Parser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitNeq(ctx: WdlV2Parser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLte(ctx: WdlV2Parser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitGt(ctx: WdlV2Parser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitAdd(ctx: WdlV2Parser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitSub(ctx: WdlV2Parser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitMod(ctx: WdlV2Parser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitMul(ctx: WdlV2Parser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitDivide(ctx: WdlV2Parser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: WdlV2Parser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: WdlV2Parser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, getSourceLocation(grammar.docSource, ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: WdlV2Parser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, getSourceLocation(grammar.docSource, ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: WdlV2Parser.Map_literalContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector

    val n = elements.size
    if (n % 2 != 0)
      throw new SyntaxException("the expressions in a map must come in pairs",
                                getSourceLocation(grammar.docSource, ctx))

    val m: Vector[ExprMember] = Vector.tabulate(n / 2) { i =>
      val key = elements(2 * i)
      val value = elements(2 * i + 1)
      ExprMember(key,
                 value,
                 SourceLocation(grammar.docSource,
                                key.loc.line,
                                key.loc.col,
                                value.loc.endLine,
                                value.loc.endCol))
    }
    ExprMapLiteral(m, getSourceLocation(grammar.docSource, ctx))
  }

  private def getMembers(members: Vector[WdlV2Parser.MemberContext],
                         values: Vector[WdlV2Parser.ExprContext],
                         ctx: ParserRuleContext): Vector[ExprMember] = {
    val ids: Vector[Expr] = members.map { m =>
      ExprString(getIdentifierText(m.Identifier(), ctx), getSourceLocation(grammar.docSource, m))
    }
    val elements: Vector[Expr] = values.map(x => visitExpr(x))
    ids.zip(elements).map { pair =>
      val id = pair._1
      val expr = pair._2
      val textSource =
        SourceLocation(grammar.docSource,
                       id.loc.line,
                       id.loc.col,
                       expr.loc.endLine,
                       expr.loc.endCol)
      ExprMember(id, expr, textSource)
    }
  }

  // | OBJECTLITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: WdlV2Parser.Object_literalContext): Expr = {
    val members = getMembers(ctx.member().asScala.toVector, ctx.expr().asScala.toVector, ctx)
    ExprObjectLiteral(members, getSourceLocation(grammar.docSource, ctx))
  }

  // | Identifier LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)* COMMA?)* RBRACE #struct_literal
  override def visitStruct_literal(ctx: WdlV2Parser.Struct_literalContext): Expr = {
    val name = ctx.Identifier().toString
    val members = getMembers(ctx.member().asScala.toVector, ctx.expr().asScala.toVector, ctx)
    ExprStructLiteral(name, members, getSourceLocation(grammar.docSource, ctx))
  }

  // | NOT expr #negate
  override def visitNegate(ctx: WdlV2Parser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, getSourceLocation(grammar.docSource, ctx))
  }

  // | (PLUS | MINUS) expr #unarysigned
  override def visitUnarysigned(ctx: WdlV2Parser.UnarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUnaryPlus(expr, getSourceLocation(grammar.docSource, ctx))
    else if (ctx.MINUS() != null)
      ExprUnaryMinus(expr, getSourceLocation(grammar.docSource, ctx))
    else
      throw new SyntaxException("bad unary expression", getSourceLocation(grammar.docSource, ctx))
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlV2Parser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, getSourceLocation(grammar.docSource, ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlV2Parser.ApplyContext): Expr = {
    val funcName = getIdentifierText(ctx.Identifier(), ctx)
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, getSourceLocation(grammar.docSource, ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: WdlV2Parser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLeft_name(ctx: WdlV2Parser.Left_nameContext): Expr = {
    val id = getIdentifierText(ctx.Identifier(), ctx)
    ExprIdentifier(id, getSourceLocation(grammar.docSource, ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlV2Parser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, getSourceLocation(grammar.docSource, ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: WdlV2Parser.Expr_infix0Context): Expr = {
    ctx match {
      case lor: WdlV2Parser.LorContext => visitLor(lor)
      case infix1: WdlV2Parser.Infix1Context =>
        visitInfix1(infix1).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: WdlV2Parser.Expr_infix1Context): Expr = {
    ctx match {
      case land: WdlV2Parser.LandContext     => visitLand(land)
      case infix2: WdlV2Parser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
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

  private def visitExpr_infix2(ctx: WdlV2Parser.Expr_infix2Context): Expr = {
    ctx match {
      case eqeq: WdlV2Parser.EqeqContext => visitEqeq(eqeq)
      case neq: WdlV2Parser.NeqContext   => visitNeq(neq)
      case lte: WdlV2Parser.LteContext   => visitLte(lte)
      case gte: WdlV2Parser.GteContext   => visitGte(gte)
      case lt: WdlV2Parser.LtContext     => visitLt(lt)
      case gt: WdlV2Parser.GtContext     => visitGt(gt)
      case infix3: WdlV2Parser.Infix3Context =>
        visitInfix3(infix3).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: WdlV2Parser.Expr_infix3Context): Expr = {
    ctx match {
      case add: WdlV2Parser.AddContext => visitAdd(add)
      case sub: WdlV2Parser.SubContext => visitSub(sub)
      case infix4: WdlV2Parser.Infix4Context =>
        visitInfix4(infix4).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: WdlV2Parser.Expr_infix4Context): Expr = {
    ctx match {
      case mul: WdlV2Parser.MulContext       => visitMul(mul)
      case divide: WdlV2Parser.DivideContext => visitDivide(divide)
      case mod: WdlV2Parser.ModContext       => visitMod(mod)
      case infix5: WdlV2Parser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */

  override def visitExpr_infix5(ctx: WdlV2Parser.Expr_infix5Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: WdlV2Parser.ExprContext): Expr = {
    try {
      visitChildren(ctx).asInstanceOf[Expr]
    } catch {
      case _: NullPointerException =>
        throw new SyntaxException("bad expression", getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* expr_core
	: LPAREN expr RPAREN #expression_group
	| primitive_literal #primitives
	| LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
	| LPAREN expr COMMA expr RPAREN #pair_literal
	| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
	| object_or_struct LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
	| NOT expr #negate
	| (PLUS | MINUS) expr #unarysigned
	| expr_core LBRACK expr RBRACK #at
	| IF expr THEN expr ELSE expr #ifthenelse
	| Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
	| Identifier #left_name
	| expr_core DOT Identifier #get_name
	; */
  private def visitExpr_core(ctx: WdlV2Parser.Expr_coreContext): Expr = {
    ctx match {
      case group: WdlV2Parser.Expression_groupContext => visitExpression_group(group)
      case primitives: WdlV2Parser.PrimitivesContext =>
        visitPrimitive_literal(primitives.primitive_literal())
      case array_literal: WdlV2Parser.Array_literalContext   => visitArray_literal(array_literal)
      case pair_literal: WdlV2Parser.Pair_literalContext     => visitPair_literal(pair_literal)
      case map_literal: WdlV2Parser.Map_literalContext       => visitMap_literal(map_literal)
      case object_literal: WdlV2Parser.Object_literalContext => visitObject_literal(object_literal)
      case struct_literal: WdlV2Parser.Struct_literalContext => visitStruct_literal(struct_literal)
      case negate: WdlV2Parser.NegateContext                 => visitNegate(negate)
      case unarysigned: WdlV2Parser.UnarysignedContext       => visitUnarysigned(unarysigned)
      case at: WdlV2Parser.AtContext                         => visitAt(at)
      case ifthenelse: WdlV2Parser.IfthenelseContext         => visitIfthenelse(ifthenelse)
      case apply: WdlV2Parser.ApplyContext                   => visitApply(apply)
      case left_name: WdlV2Parser.Left_nameContext           => visitLeft_name(left_name)
      case get_name: WdlV2Parser.Get_nameContext             => visitGet_name(get_name)
      case _ =>
        throw new SyntaxException("bad expression", getSourceLocation(grammar.docSource, ctx))
    }
  }

  /*
unbound_decls
	: wdl_type Identifier
	;
   */
  override def visitUnbound_decls(ctx: WdlV2Parser.Unbound_declsContext): Declaration = {
    if (ctx.wdl_type() == null)
      throw new SyntaxException("type missing in declaration",
                                getSourceLocation(grammar.docSource, ctx))
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = getIdentifierText(ctx.Identifier(), ctx)
    Declaration(name, wdlType, None, getSourceLocation(grammar.docSource, ctx))
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: WdlV2Parser.Bound_declsContext): Declaration = {
    if (ctx.wdl_type() == null)
      throw new SyntaxException("type missing in declaration",
                                getSourceLocation(grammar.docSource, ctx))
    val wdlType = visitWdl_type(ctx.wdl_type())
    val name: String = getIdentifierText(ctx.Identifier(), ctx)
    if (ctx.expr() == null) {
      Declaration(name, wdlType, None, getSourceLocation(grammar.docSource, ctx))
    } else {
      val expr: Expr = visitExpr(ctx.expr())
      Declaration(name, wdlType, Some(expr), getSourceLocation(grammar.docSource, ctx))
    }
  }

  /*
any_decls
	: unbound_decls
	| bound_decls
	;
   */
  override def visitAny_decls(ctx: WdlV2Parser.Any_declsContext): Declaration = {
    if (ctx.unbound_decls() != null)
      return visitUnbound_decls(ctx.unbound_decls())
    if (ctx.bound_decls() != null)
      return visitBound_decls(ctx.bound_decls())
    throw new SyntaxException("bad declaration format", getSourceLocation(grammar.docSource, ctx))
  }

  /* meta_value
    : BoolLiteral
    | number
    | string
    | meta_object
    | meta_array
    | NULL_LITERAL
    ; */
  override def visitMeta_value(ctx: WdlV2Parser.Meta_valueContext): MetaValue = {
    if (ctx.MetaNull() != null) {
      return MetaValueNull(getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.MetaBool() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      return MetaValueBoolean(value, getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.MetaInt() != null) {
      return MetaValueInt(ctx.MetaInt().getText.toLong, getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.MetaFloat() != null) {
      return MetaValueFloat(ctx.MetaFloat().getText.toDouble,
                            getSourceLocation(grammar.docSource, ctx))
    }
    if (ctx.meta_string() != null) {
      return visitMeta_string(ctx.meta_string())
    }
    if (ctx.meta_array() != null) {
      return visitMeta_array(ctx.meta_array())
    }
    if (ctx.meta_object() != null) {
      return visitMeta_object(ctx.meta_object())
    }
    throw new SyntaxException("Not one of four supported variants of meta_value",
                              getSourceLocation(grammar.docSource, ctx))
  }

  /* meta_string
    : DQUOTE string_part DQUOTE
    | SQUOTE string_part SQUOTE
    ; */
  override def visitMeta_string(ctx: WdlV2Parser.Meta_stringContext): MetaValueString = {
    MetaValueString(
        ctx.meta_string_part().MetaStringPart().asScala.toVector.map(x => x.getText).mkString,
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  /* meta_array: LBRACK (meta_value (COMMA meta_value)*)* RBRACK;
   */
  override def visitMeta_array(ctx: WdlV2Parser.Meta_arrayContext): MetaValueArray = {
    val items = if (ctx.MetaEmptyArray() != null) {
      Vector.empty
    } else {
      ctx.meta_value().asScala.toVector.map(visitMeta_value)
    }
    MetaValueArray(items, getSourceLocation(grammar.docSource, ctx))
  }

  private def visitMeta_kv(identifier: TerminalNode,
                           meta_value: WdlV2Parser.Meta_valueContext,
                           parent: ParserRuleContext) = {
    val id = getIdentifierText(identifier, parent)
    if (meta_value == null) {
      throw new SyntaxException(s"Invalid expression for meta key ${id}",
                                getSourceLocation(grammar.docSource, parent))
    }
    val value = visitMeta_value(meta_value)
    MetaKV(id, value, getSourceLocation(grammar.docSource, parent))
  }

  /* meta_object: LBRACE (meta_kv (COMMA meta_kv)*)* RBRACE;
   */
  override def visitMeta_object(ctx: WdlV2Parser.Meta_objectContext): MetaValueObject = {
    val members = ctx
      .meta_object_kv()
      .asScala
      .toVector
      .map(member => visitMeta_kv(member.MetaObjectIdentifier(), member.meta_value(), member))
    MetaValueObject(members, getSourceLocation(grammar.docSource, ctx))
  }

  /* meta_kv
     : Identifier COLON expr
     ; */
  override def visitMeta_kv(ctx: WdlV2Parser.Meta_kvContext): MetaKV = {
    visitMeta_kv(ctx.MetaIdentifier(), ctx.meta_value(), ctx)
  }

  //  PARAMETERMETA LBRACE meta_kv* RBRACE #parameter_meta
  override def visitParameter_meta(
      ctx: WdlV2Parser.Parameter_metaContext
  ): ParameterMetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    ParameterMetaSection(kvs, getSourceLocation(grammar.docSource, ctx))
  }

  //  META LBRACE meta_kv* RBRACE #meta
  override def visitMeta(ctx: WdlV2Parser.MetaContext): MetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    MetaSection(kvs, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitTask_hints(ctx: WdlV2Parser.Task_hintsContext): HintsSection = {
    val kvs = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    HintsSection(kvs, getSourceLocation(grammar.docSource, ctx))
  }

  private val legalRuntimeKeys = Set(
      "cpu",
      "container",
      "memory",
      "gpu",
      "disks",
      "maxRetries",
      "returnCodes"
  )

  /* task_runtime_kv
   : Identifier COLON expr
   ; */
  override def visitTask_runtime_kv(ctx: WdlV2Parser.Task_runtime_kvContext): RuntimeKV = {
    val id: String = getIdentifierText(ctx.Identifier(), ctx)
    if (!legalRuntimeKeys.contains(id)) {
      throw new SyntaxException(s"invalid runtime keyword ${id}",
                                getSourceLocation(grammar.docSource, ctx))
    }
    val expr: Expr = visitExpr(ctx.expr())
    RuntimeKV(id, expr, getSourceLocation(grammar.docSource, ctx))
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: WdlV2Parser.Task_runtimeContext): RuntimeSection = {
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitTask_runtime_kv(x))
      .toVector
    RuntimeSection(kvs, getSourceLocation(grammar.docSource, ctx))
  }

  /*
task_input
	: INPUT LBRACE (any_decls)* RBRACE
	;
   */
  override def visitTask_input(ctx: WdlV2Parser.Task_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, getSourceLocation(grammar.docSource, ctx))
  }

  /* task_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	; */
  override def visitTask_output(ctx: WdlV2Parser.Task_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, getSourceLocation(grammar.docSource, ctx))
  }

  /* task_command_string_part
    : CommandStringPart*
    ; */
  override def visitTask_command_string_part(
      ctx: WdlV2Parser.Task_command_string_partContext
  ): ExprString = {
    val text: String = ctx
      .CommandStringPart()
      .asScala
      .map(x => x.getText)
      .mkString("")
    ExprString(text, getSourceLocation(grammar.docSource, ctx))
  }

  /* task_command_expr_with_string
    : task_command_expr_part task_command_string_part
    ; */
  override def visitTask_command_expr_with_string(
      ctx: WdlV2Parser.Task_command_expr_with_stringContext
  ): Expr = {
    val exprPart: Expr = visitExpr(ctx.task_command_expr_part().expr())
    val stringPart: Expr = visitTask_command_string_part(
        ctx.task_command_string_part()
    )
    (exprPart, stringPart) match {
      case (e, ExprString(s, _)) if s.isEmpty => e
      case (ExprString(e, _), s) if e.isEmpty => s
      case (e, s) =>
        ExprCompoundString(Vector(e, s), getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: WdlV2Parser.Task_commandContext): CommandSection = {
    val start: Expr = visitTask_command_string_part(ctx.task_command_string_part())
    val parts: Vector[Expr] = ctx
      .task_command_expr_with_string()
      .asScala
      .map(x => visitTask_command_expr_with_string(x))
      .toVector
    // discard empty strings, and flatten compound vectors of strings
    val cleanedParts = (start +: parts).flatMap {
      case ExprString(x, _) if x.isEmpty => Vector.empty
      case ExprCompoundString(v, _)      => v
      case other                         => Vector(other)
    }
    CommandSection(cleanedParts, getSourceLocation(grammar.docSource, ctx))
  }

  // A that should appear zero or once. Make sure this is the case.
  private def atMostOneSection[T](sections: Vector[T],
                                  sectionName: String,
                                  ctx: ParserRuleContext): Option[T] = {
    sections.size match {
      case 0 => None
      case 1 => Some(sections.head)
      case n =>
        throw new SyntaxException(
            s"section ${sectionName} appears ${n} times, it cannot appear more than once",
            getSourceLocation(grammar.docSource, ctx)
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
        throw new SyntaxException(
            s"section ${sectionName} appears ${n} times, it must appear exactly once",
            getSourceLocation(grammar.docSource, ctx)
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
    if (both.nonEmpty) {
      for (varName <- both) {
        // issue a warning with the exact text where this occurs
        val decl: Declaration = inputSection.get.declarations.find(decl => decl.name == varName).get
        val text = decl.loc
        Logger.get.warning(
            s"'${varName}' appears in both input and output sections at ${text} in ${grammar.docSource}"
        )
      }
    }

    val ioVarNames = inputVarNames ++ outputVarNames

    paramMeta.kvs.foreach {
      case MetaKV(k, _, _) =>
        if (!(ioVarNames contains k))
          throw new SyntaxException(
              s"parameter ${k} does not appear in the input or output sections",
              getSourceLocation(grammar.docSource, ctx)
          )
    }
  }

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: WdlV2Parser.TaskContext): Task = {
    val name = getIdentifierText(ctx.Identifier(), ctx)
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
    val hints: Option[HintsSection] = atMostOneSection(elems.collect {
      case x: HintsSection => x
    }, sectionName = "hints", ctx)
    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Task(
        name,
        input = input,
        output = output,
        command = command,
        declarations = decls,
        meta = meta,
        parameterMeta = parameterMeta,
        runtime = runtime,
        hints = hints,
        loc = getSourceLocation(grammar.docSource, ctx)
    )
  }

  def visitImport_addr(ctx: WdlV2Parser.StringContext): ImportAddr = {
    val addr = ctx.getText.replaceAll("\"", "")
    ImportAddr(addr, getSourceLocation(grammar.docSource, ctx))
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: WdlV2Parser.Import_aliasContext): ImportAlias = {
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    ImportAlias(ids(0), ids(1), getSourceLocation(grammar.docSource, ctx))
  }

  /*
    import_as
        : AS Identifier
        ;
   */
  override def visitImport_as(ctx: WdlV2Parser.Import_asContext): ImportName = {
    ImportName(ctx.Identifier().getText, getSourceLocation(grammar.docSource, ctx))
  }

  /*
 import_doc
	: IMPORT string import_as? (import_alias)*
	;
   */
  override def visitImport_doc(ctx: WdlV2Parser.Import_docContext): ImportDoc = {
    val addr = visitImport_addr(ctx.string())
    val name =
      if (ctx.import_as() == null)
        None
      else
        Some(visitImport_as(ctx.import_as()))

    val aliases = ctx
      .import_alias()
      .asScala
      .map(x => visitImport_alias(x))
      .toVector
    ImportDoc(name, aliases, addr, getSourceLocation(grammar.docSource, ctx))
  }

  override def visitCall_after(ctx: WdlV2Parser.Call_afterContext): CallAfter = {
    CallAfter(getIdentifierText(ctx.Identifier(), ctx), getSourceLocation(grammar.docSource, ctx))
  }

  /* call_alias
    : AS Identifier
    ; */
  override def visitCall_alias(ctx: WdlV2Parser.Call_aliasContext): CallAlias = {
    CallAlias(getIdentifierText(ctx.Identifier(), ctx), getSourceLocation(grammar.docSource, ctx))
  }

  /* call_input
	: Identifier EQUAL expr
	; */
  override def visitCall_input(ctx: WdlV2Parser.Call_inputContext): CallInput = {
    val id = getIdentifierText(ctx.Identifier(), ctx)
    val loc = getSourceLocation(grammar.docSource, ctx)
    val expr = if (ctx.expr() == null) {
      // pass-through
      ExprIdentifier(id, loc)
    } else {
      visitExpr(ctx.expr())
    }
    CallInput(id, expr, loc)
  }

  /* call_inputs
	: INPUT COLON (call_input (COMMA call_input)*)
	; */
  override def visitCall_inputs(ctx: WdlV2Parser.Call_inputsContext): CallInputs = {
    val inputs: Vector[CallInput] = ctx
      .call_input()
      .asScala
      .map { x =>
        visitCall_input(x)
      }
      .toVector
    CallInputs(inputs, getSourceLocation(grammar.docSource, ctx))
  }

  /* call_body
	: LBRACE call_inputs? RBRACE
	; */
  override def visitCall_body(ctx: WdlV2Parser.Call_bodyContext): CallInputs = {
    if (ctx.call_inputs() == null)
      CallInputs(Vector.empty, getSourceLocation(grammar.docSource, ctx))
    else
      visitCall_inputs(ctx.call_inputs())
  }

  /* call
	: CALL Identifier call_alias?  call_body?
	; */
  override def visitCall(ctx: WdlV2Parser.CallContext): Call = {
    val name = ctx.call_name().getText

    val alias: Option[CallAlias] = if (ctx.call_alias() == null) {
      None
    } else {
      Some(visitCall_alias(ctx.call_alias()))
    }

    val afters: Vector[CallAfter] = if (ctx.call_after() == null) {
      Vector.empty
    } else {
      ctx.call_after().asScala.map(x => visitCall_after(x)).toVector
    }

    val inputs: Option[CallInputs] =
      if (ctx.call_body() == null) {
        None
      } else {
        Some(visitCall_body(ctx.call_body()))
      }

    Call(name, alias, afters, inputs, getSourceLocation(grammar.docSource, ctx))
  }

  /*
scatter
	: SCATTER LPAREN Identifier In expr RPAREN LBRACE inner_workflow_element* RBRACE
 ; */
  override def visitScatter(ctx: WdlV2Parser.ScatterContext): Scatter = {
    val id = getIdentifierText(ctx.Identifier(), ctx)
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Scatter(id, expr, body, getSourceLocation(grammar.docSource, ctx))
  }

  /* conditional
	: IF LPAREN expr RPAREN LBRACE inner_workflow_element* RBRACE
	; */
  override def visitConditional(ctx: WdlV2Parser.ConditionalContext): Conditional = {
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Conditional(expr, body, getSourceLocation(grammar.docSource, ctx))
  }

  /* workflow_input
	: INPUT LBRACE (any_decls)* RBRACE
	; */
  override def visitWorkflow_input(ctx: WdlV2Parser.Workflow_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, getSourceLocation(grammar.docSource, ctx))
  }

  /* workflow_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	;
   */
  override def visitWorkflow_output(ctx: WdlV2Parser.Workflow_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, getSourceLocation(grammar.docSource, ctx))
  }

  /* inner_workflow_element
	: bound_decls
	| call
	| scatter
	| conditional
	; */
  override def visitInner_workflow_element(
      ctx: WdlV2Parser.Inner_workflow_elementContext
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
  override def visitWorkflow(ctx: WdlV2Parser.WorkflowContext): Workflow = {
    val name = getIdentifierText(ctx.Identifier(), ctx)
    val elems: Vector[WdlV2Parser.Workflow_elementContext] =
      ctx.workflow_element().asScala.toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: WdlV2Parser.InputContext =>
        visitWorkflow_input(x.workflow_input())
    }, "input", ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: WdlV2Parser.OutputContext =>
        visitWorkflow_output(x.workflow_output())
    }, "output", ctx)
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: WdlV2Parser.Meta_elementContext =>
        visitMeta(x.meta())
    }, "meta", ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: WdlV2Parser.Parameter_meta_elementContext =>
        visitParameter_meta(x.parameter_meta())
    }, "parameter_meta", ctx)
    val wfElems: Vector[WorkflowElement] = elems.collect {
      case x: WdlV2Parser.Inner_elementContext =>
        visitInner_workflow_element(x.inner_workflow_element())
    }

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Workflow(name,
             input,
             output,
             meta,
             parameterMeta,
             wfElems,
             getSourceLocation(grammar.docSource, ctx))
  }

  /*
document_element
	: import_doc
	| struct
	| task
	;
   */
  override def visitDocument_element(ctx: WdlV2Parser.Document_elementContext): DocumentElement = {
    visitChildren(ctx).asInstanceOf[DocumentElement]
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: WdlV2Parser.VersionContext): Version = {
    if (ctx.ReleaseVersion() == null) {
      throw new Exception("version not specified")
    }
    val value = ctx.ReleaseVersion().getText
    Version(WdlVersion.withName(value), getSourceLocation(grammar.docSource, ctx))
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  def visitDocument(ctx: WdlV2Parser.DocumentContext, comments: CommentMap): Document = {
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

    Document(grammar.docSource,
             version,
             elems,
             workflow,
             getSourceLocation(grammar.docSource, ctx),
             comments)
  }

  def visitExprDocument(ctx: WdlV2Parser.Expr_documentContext): Expr = {
    visitExpr(ctx.expr())
  }

  def visitTypeDocument(ctx: WdlV2Parser.Type_documentContext): Type = {
    visitWdl_type(ctx.wdl_type())
  }

  def parseDocument: Document = {
    grammar
      .visitDocument[WdlV2Parser.DocumentContext, Document](grammar.parser.document, visitDocument)
  }

  def parseExpr: Expr = {
    grammar.visitFragment[WdlV2Parser.Expr_documentContext, Expr](grammar.parser.expr_document,
                                                                  visitExprDocument)
  }

  def parseWdlType: Type = {
    grammar.visitFragment[WdlV2Parser.Type_documentContext, Type](grammar.parser.type_document,
                                                                  visitTypeDocument)
  }
}
