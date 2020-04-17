package wdlTools.syntax.v1_0

// Parse one document. Do not follow imports.

import org.antlr.v4.runtime._
import org.openwdl.wdl.parser.v1_0._
import wdlTools.syntax.Antlr4Util.Grammar
import wdlTools.syntax.v1_0.ConcreteSyntax._
import wdlTools.syntax.{SyntaxException, WdlVersion}
import wdlTools.util.{Options, Util}

import scala.collection.JavaConverters._
import scala.collection.mutable

case class ParseTop(opts: Options, grammar: Grammar[WdlV1Lexer, WdlV1Parser])
    extends WdlV1ParserBaseVisitor[Element] {
  /*
struct
	: STRUCT Identifier LBRACE (unbound_decls)* RBRACE
	;
   */
  override def visitStruct(ctx: WdlV1Parser.StructContext): TypeStruct = {
    // grammar.notifyParserListeners(ctx)

    val sName = ctx.Identifier().getText
    val members: Vector[StructMember] = ctx
      .unbound_decls()
      .asScala
      .map { x =>
        val decl = visitUnbound_decls(x)
        StructMember(decl.name, decl.wdlType, decl.text, decl.comment)
      }
      .toVector

    // check that each field appears once
    val memberNames: mutable.Set[String] = mutable.HashSet.empty
    members.foreach { member =>
      if (memberNames.contains(member.name)) {
        throw new SyntaxException(s"struct ${sName} has field ${member.name} defined twice",
                                  grammar.getSourceText(ctx))
      }
      memberNames.add(member.name)
    }

    TypeStruct(sName, members, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /*
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: WdlV1Parser.Map_typeContext): Type = {
    // grammar.notifyParserListeners(ctx)
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt, grammar.getSourceText(ctx))
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: WdlV1Parser.Array_typeContext): Type = {
    // grammar.notifyParserListeners(ctx)
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = ctx.PLUS() != null
    TypeArray(t, nonEmpty, grammar.getSourceText(ctx))
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: WdlV1Parser.Pair_typeContext): Type = {
    // grammar.notifyParserListeners(ctx)
    val lt: Type = visitWdl_type(ctx.wdl_type(0))
    val rt: Type = visitWdl_type(ctx.wdl_type(1))
    TypePair(lt, rt, grammar.getSourceText(ctx))
  }

  /*
type_base
	: array_type
	| map_type
	| pair_type
	| (STRING | FILE | BOOLEAN | OBJECT | INT | FLOAT | Identifier)
	;
   */
  override def visitType_base(ctx: WdlV1Parser.Type_baseContext): Type = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.array_type() != null)
      return visitArray_type(ctx.array_type())
    if (ctx.map_type() != null)
      return visitMap_type(ctx.map_type())
    if (ctx.pair_type() != null)
      return visitPair_type(ctx.pair_type())
    if (ctx.STRING() != null)
      return TypeString(grammar.getSourceText(ctx))
    if (ctx.FILE() != null)
      return TypeFile(grammar.getSourceText(ctx))
    if (ctx.BOOLEAN() != null)
      return TypeBoolean(grammar.getSourceText(ctx))
    if (ctx.OBJECT() != null)
      return TypeObject(grammar.getSourceText(ctx))
    if (ctx.INT() != null)
      return TypeInt(grammar.getSourceText(ctx))
    if (ctx.FLOAT() != null)
      return TypeFloat(grammar.getSourceText(ctx))
    if (ctx.Identifier() != null)
      return TypeIdentifier(ctx.getText, grammar.getSourceText(ctx))
    throw new SyntaxException("sanity: unrecgonized type case", grammar.getSourceText(ctx))
  }

  /*
wdl_type
  : (type_base OPTIONAL | type_base)
  ;
   */
  override def visitWdl_type(ctx: WdlV1Parser.Wdl_typeContext): Type = {
    // grammar.notifyParserListeners(ctx)
    val t = visitType_base(ctx.type_base())
    if (ctx.OPTIONAL() != null) {
      TypeOptional(t, grammar.getSourceText(ctx))
    } else {
      t
    }
  }

  // EXPRESSIONS

  override def visitNumber(ctx: WdlV1Parser.NumberContext): Expr = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.IntLiteral() != null) {
      return ExprInt(ctx.getText.toInt, grammar.getSourceText(ctx))
    }
    if (ctx.FloatLiteral() != null) {
      return ExprFloat(ctx.getText.toDouble, grammar.getSourceText(ctx))
    }
    throw new SyntaxException(s"Not an integer nor a float ${ctx.getText}",
                              grammar.getSourceText(ctx))
  }

  /* expression_placeholder_option
  : BoolLiteral EQUAL (string | number)
  | DEFAULT EQUAL (string | number)
  | SEP EQUAL (string | number)
  ; */
  override def visitExpression_placeholder_option(
      ctx: WdlV1Parser.Expression_placeholder_optionContext
  ): PlaceHolderPart = {
    // grammar.notifyParserListeners(ctx)
    val expr: Expr =
      if (ctx.string() != null)
        visitString(ctx.string())
      else if (ctx.number() != null)
        visitNumber(ctx.number())
      else
        throw new SyntaxException("sanity: not a string or a number", grammar.getSourceText(ctx))

    if (ctx.BoolLiteral() != null) {
      val b = ctx.BoolLiteral().getText.toLowerCase() == "true"
      return ExprPlaceholderPartEqual(b, expr, grammar.getSourceText(ctx))
    }
    if (ctx.DEFAULT() != null) {
      return ExprPlaceholderPartDefault(expr, grammar.getSourceText(ctx))
    }
    if (ctx.SEP() != null) {
      return ExprPlaceholderPartSep(expr, grammar.getSourceText(ctx))
    }
    throw new SyntaxException(s"Not one of three known variants of a placeholder",
                              grammar.getSourceText(ctx))
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
    val source = grammar.getSourceText(ctx)

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
          throw new SyntaxException("invalid place holder", grammar.getSourceText(ctx))
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
          throw new SyntaxException("invalid boolean place holder", grammar.getSourceText(ctx))
        case (_, _) =>
          throw new SyntaxException("invalid place holder", grammar.getSourceText(ctx))
      }
    }

    throw new SyntaxException("invalid place holder", grammar.getSourceText(ctx))
  }

  /* string_part
  : StringPart*
  ; */
  override def visitString_part(ctx: WdlV1Parser.String_partContext): ExprCompoundString = {
    // grammar.notifyParserListeners(ctx)
    val parts: Vector[Expr] = ctx
      .StringPart()
      .asScala
      .map(x => ExprString(x.getText, grammar.getSourceText(x)))
      .toVector
    ExprCompoundString(parts, grammar.getSourceText(ctx))
  }

  /* string_expr_part
  : StringCommandStart (expression_placeholder_option)* expr RBRACE
  ; */
  override def visitString_expr_part(ctx: WdlV1Parser.String_expr_partContext): Expr = {
    // grammar.notifyParserListeners(ctx)
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
      ctx: WdlV1Parser.String_expr_with_string_partContext
  ): ExprCompoundString = {
    // grammar.notifyParserListeners(ctx)
    val exprPart = visitString_expr_part(ctx.string_expr_part())
    val strPart = visitString_part(ctx.string_part())
    ExprCompoundString(Vector(exprPart, strPart), grammar.getSourceText(ctx))
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  override def visitString(ctx: WdlV1Parser.StringContext): Expr = {
    // grammar.notifyParserListeners(ctx)
    val stringPart = ExprString(ctx.string_part().getText, grammar.getSourceText(ctx.string_part()))
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
      ExprCompoundString(Vector(stringPart) ++ exprPart2, grammar.getSourceText(ctx))
    }
  }

  /* primitive_literal
	: BoolLiteral
	| number
	| string
	| Identifier
	; */
  override def visitPrimitive_literal(ctx: WdlV1Parser.Primitive_literalContext): Expr = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      return ExprBoolean(value, grammar.getSourceText(ctx))
    }
    if (ctx.number() != null) {
      return visitNumber(ctx.number())
    }
    if (ctx.string() != null) {
      return visitString(ctx.string())
    }
    if (ctx.Identifier() != null) {
      return ExprIdentifier(ctx.getText, grammar.getSourceText(ctx))
    }
    throw new SyntaxException("Not one of four supported variants of primitive_literal",
                              grammar.getSourceText(ctx))
  }

  override def visitLor(ctx: WdlV1Parser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitLand(ctx: WdlV1Parser.LandContext): Expr = {
    // grammar.notifyParserListeners(ctx)
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitEqeq(ctx: WdlV1Parser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1, grammar.getSourceText(ctx))
  }
  override def visitLt(ctx: WdlV1Parser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitGte(ctx: WdlV1Parser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitNeq(ctx: WdlV1Parser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitLte(ctx: WdlV1Parser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitGt(ctx: WdlV1Parser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitAdd(ctx: WdlV1Parser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitSub(ctx: WdlV1Parser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitMod(ctx: WdlV1Parser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitMul(ctx: WdlV1Parser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1, grammar.getSourceText(ctx))
  }

  override def visitDivide(ctx: WdlV1Parser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1, grammar.getSourceText(ctx))
  }

  // | LPAREN expr RPAREN #expression_group
  override def visitExpression_group(ctx: WdlV1Parser.Expression_groupContext): Expr = {
    visitExpr(ctx.expr())
  }

  // | LBRACK (expr (COMMA expr)*)* RBRACK #array_literal
  override def visitArray_literal(ctx: WdlV1Parser.Array_literalContext): Expr = {
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprArrayLiteral(elements, grammar.getSourceText(ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: WdlV1Parser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1, grammar.getSourceText(ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: WdlV1Parser.Map_literalContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector

    val n = elements.size
    if (n % 2 != 0)
      throw new SyntaxException("the expressions in a map must come in pairs",
                                grammar.getSourceText(ctx))

    val m: Map[Expr, Expr] =
      Vector.tabulate(n / 2)(i => elements(2 * i) -> elements(2 * i + 1)).toMap
    ExprMapLiteral(m, grammar.getSourceText(ctx))
  }

  // | OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: WdlV1Parser.Object_literalContext): Expr = {
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
    ExprObjectLiteral((ids zip elements).toMap, grammar.getSourceText(ctx))
  }

  // | NOT expr #negate
  override def visitNegate(ctx: WdlV1Parser.NegateContext): Expr = {
    val expr = visitExpr(ctx.expr())
    ExprNegate(expr, grammar.getSourceText(ctx))
  }

  // | (PLUS | MINUS) expr #unirarysigned
  override def visitUnirarysigned(ctx: WdlV1Parser.UnirarysignedContext): Expr = {
    val expr = visitExpr(ctx.expr())

    if (ctx.PLUS() != null)
      ExprUniraryPlus(expr, grammar.getSourceText(ctx))
    else if (ctx.MINUS() != null)
      ExprUniraryMinus(expr, grammar.getSourceText(ctx))
    else
      throw new SyntaxException("sanity", grammar.getSourceText(ctx))
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlV1Parser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index, grammar.getSourceText(ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlV1Parser.ApplyContext): Expr = {
    val funcName = ctx.Identifier().getText
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements, grammar.getSourceText(ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: WdlV1Parser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2), grammar.getSourceText(ctx))
  }

  override def visitLeft_name(ctx: WdlV1Parser.Left_nameContext): Expr = {
    val id = ctx.Identifier().getText
    ExprIdentifier(id, grammar.getSourceText(ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlV1Parser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id, grammar.getSourceText(ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: WdlV1Parser.Expr_infix0Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case lor: WdlV1Parser.LorContext => visitLor(lor)
      case infix1: WdlV1Parser.Infix1Context =>
        visitInfix1(infix1).asInstanceOf[Expr]
    }
  }

  /* expr_infix1
	: expr_infix1 AND expr_infix2 #land
	| expr_infix2 #infix2
	; */
  private def visitExpr_infix1(ctx: WdlV1Parser.Expr_infix1Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case land: WdlV1Parser.LandContext     => visitLand(land)
      case infix2: WdlV1Parser.Infix2Context => visitInfix2(infix2).asInstanceOf[Expr]
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

  private def visitExpr_infix2(ctx: WdlV1Parser.Expr_infix2Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case eqeq: WdlV1Parser.EqeqContext => visitEqeq(eqeq)
      case neq: WdlV1Parser.NeqContext   => visitNeq(neq)
      case lte: WdlV1Parser.LteContext   => visitLte(lte)
      case gte: WdlV1Parser.GteContext   => visitGte(gte)
      case lt: WdlV1Parser.LtContext     => visitLt(lt)
      case gt: WdlV1Parser.GtContext     => visitGt(gt)
      case infix3: WdlV1Parser.Infix3Context =>
        visitInfix3(infix3).asInstanceOf[Expr]
    }
  }

  /* expr_infix3
	: expr_infix3 PLUS expr_infix4 #add
	| expr_infix3 MINUS expr_infix4 #sub
	| expr_infix4 #infix4
	; */
  private def visitExpr_infix3(ctx: WdlV1Parser.Expr_infix3Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case add: WdlV1Parser.AddContext => visitAdd(add)
      case sub: WdlV1Parser.SubContext => visitSub(sub)
      case infix4: WdlV1Parser.Infix4Context =>
        visitInfix4(infix4).asInstanceOf[Expr]
    }
  }

  /* expr_infix4
	: expr_infix4 STAR expr_infix5 #mul
	| expr_infix4 DIVIDE expr_infix5 #divide
	| expr_infix4 MOD expr_infix5 #mod
	| expr_infix5 #infix5
	;  */
  private def visitExpr_infix4(ctx: WdlV1Parser.Expr_infix4Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case mul: WdlV1Parser.MulContext       => visitMul(mul)
      case divide: WdlV1Parser.DivideContext => visitDivide(divide)
      case mod: WdlV1Parser.ModContext       => visitMod(mod)
      case infix5: WdlV1Parser.Infix5Context => visitInfix5(infix5).asInstanceOf[Expr]
    }
  }

  /* expr_infix5
	: expr_core
	; */

  override def visitExpr_infix5(ctx: WdlV1Parser.Expr_infix5Context): Expr = {
    // grammar.notifyParserListeners(ctx)
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: WdlV1Parser.ExprContext): Expr = {
    // grammar.notifyParserListeners(ctx)
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
  private def visitExpr_core(ctx: WdlV1Parser.Expr_coreContext): Expr = {
    // grammar.notifyParserListeners(ctx)
    ctx match {
      case group: WdlV1Parser.Expression_groupContext => visitExpression_group(group)
      case primitives: WdlV1Parser.PrimitivesContext =>
        visitPrimitive_literal(primitives.primitive_literal())
      case array_literal: WdlV1Parser.Array_literalContext => visitArray_literal(array_literal)
      case pair_literal: WdlV1Parser.Pair_literalContext   => visitPair_literal(pair_literal)
      case map_literal: WdlV1Parser.Map_literalContext     => visitMap_literal(map_literal)
      case obj_literal: WdlV1Parser.Object_literalContext  => visitObject_literal(obj_literal)
      case negate: WdlV1Parser.NegateContext               => visitNegate(negate)
      case unirarysigned: WdlV1Parser.UnirarysignedContext => visitUnirarysigned(unirarysigned)
      case at: WdlV1Parser.AtContext                       => visitAt(at)
      case ifthenelse: WdlV1Parser.IfthenelseContext       => visitIfthenelse(ifthenelse)
      case apply: WdlV1Parser.ApplyContext                 => visitApply(apply)
      case left_name: WdlV1Parser.Left_nameContext         => visitLeft_name(left_name)
      case get_name: WdlV1Parser.Get_nameContext           => visitGet_name(get_name)
    }
  }

  /*
unbound_decls
	: wdl_type Identifier
	;
   */
  override def visitUnbound_decls(ctx: WdlV1Parser.Unbound_declsContext): Declaration = {
    // grammar.notifyParserListeners(ctx)
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText
    Declaration(name, wdlType, None, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: WdlV1Parser.Bound_declsContext): Declaration = {
    // grammar.notifyParserListeners(ctx)
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = ctx.Identifier().getText
    if (ctx.expr() == null)
      return Declaration(name, wdlType, None, grammar.getSourceText(ctx), grammar.getComment(ctx))
    val expr: Expr = visitExpr(ctx.expr())
    Declaration(name, wdlType, Some(expr), grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /*
any_decls
	: unbound_decls
	| bound_decls
	;
   */
  override def visitAny_decls(ctx: WdlV1Parser.Any_declsContext): Declaration = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.unbound_decls() != null)
      return visitUnbound_decls(ctx.unbound_decls())
    if (ctx.bound_decls() != null)
      return visitBound_decls(ctx.bound_decls())
    throw new Exception("sanity")
  }

  /* meta_kv
   : Identifier COLON expr
   ; */
  override def visitMeta_kv(ctx: WdlV1Parser.Meta_kvContext): MetaKV = {
    // grammar.notifyParserListeners(ctx)
    val id = ctx.Identifier().getText
    val expr = visitExpr(ctx.expr())
    MetaKV(id, expr, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  //  PARAMETERMETA LBRACE meta_kv* RBRACE #parameter_meta
  override def visitParameter_meta(
      ctx: WdlV1Parser.Parameter_metaContext
  ): ParameterMetaSection = {
    // grammar.notifyParserListeners(ctx)
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    ParameterMetaSection(kvs, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  //  META LBRACE meta_kv* RBRACE #meta
  override def visitMeta(ctx: WdlV1Parser.MetaContext): MetaSection = {
    // grammar.notifyParserListeners(ctx)
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    MetaSection(kvs, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* task_runtime_kv
 : Identifier COLON expr
 ; */
  override def visitTask_runtime_kv(ctx: WdlV1Parser.Task_runtime_kvContext): RuntimeKV = {
    // grammar.notifyParserListeners(ctx)
    val id: String = ctx.Identifier.getText
    val expr: Expr = visitExpr(ctx.expr())
    RuntimeKV(id, expr, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: WdlV1Parser.Task_runtimeContext): RuntimeSection = {
    // grammar.notifyParserListeners(ctx)
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitTask_runtime_kv(x))
      .toVector
    RuntimeSection(kvs, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /*
task_input
	: INPUT LBRACE (any_decls)* RBRACE
	;
   */
  override def visitTask_input(ctx: WdlV1Parser.Task_inputContext): InputSection = {
    // grammar.notifyParserListeners(ctx)
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* task_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	; */
  override def visitTask_output(ctx: WdlV1Parser.Task_outputContext): OutputSection = {
    // grammar.notifyParserListeners(ctx)
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* task_command_string_part
    : CommandStringPart*
    ; */
  override def visitTask_command_string_part(
      ctx: WdlV1Parser.Task_command_string_partContext
  ): ExprString = {
    // grammar.notifyParserListeners(ctx)
    val text: String = ctx
      .CommandStringPart()
      .asScala
      .map(x => x.getText)
      .mkString("")
    ExprString(text, grammar.getSourceText(ctx))
  }

  /* task_command_expr_part
    : StringCommandStart  (expression_placeholder_option)* expr RBRACE
    ; */
  override def visitTask_command_expr_part(
      ctx: WdlV1Parser.Task_command_expr_partContext
  ): Expr = {
    // grammar.notifyParserListeners(ctx)
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
      ctx: WdlV1Parser.Task_command_expr_with_stringContext
  ): ExprCompoundString = {
    // grammar.notifyParserListeners(ctx)
    val exprPart: Expr = visitTask_command_expr_part(ctx.task_command_expr_part())
    val stringPart: Expr = visitTask_command_string_part(
        ctx.task_command_string_part()
    )
    ExprCompoundString(Vector(exprPart, stringPart), grammar.getSourceText(ctx))
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: WdlV1Parser.Task_commandContext): CommandSection = {
    // grammar.notifyParserListeners(ctx)
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

    CommandSection(cleanedParts, grammar.getSourceText(ctx), grammar.getComment(ctx))
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
            grammar.getSourceText(ctx)
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
            grammar.getSourceText(ctx)
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
        val text = decl.text
        Util.warning(
            s"""|Warning: "${varName}" appears in both input and output sections.
                |In file ${text.url} line ${text.line} col ${text.col}""".stripMargin
              .replaceAll("\n", " "),
            opts.verbosity
        )
      }
    }

    val ioVarNames = inputVarNames ++ outputVarNames

    paramMeta.kvs.foreach {
      case MetaKV(k, _, _, _) =>
        if (!(ioVarNames contains k))
          throw new SyntaxException(
              s"parameter ${k} does not appear in the input or output sections",
              grammar.getSourceText(ctx)
          )
    }
  }

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: WdlV1Parser.TaskContext): Task = {
    // grammar.notifyParserListeners(ctx)
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

    Task(
        name,
        input = input,
        output = output,
        command = command,
        declarations = decls,
        meta = meta,
        parameterMeta = parameterMeta,
        runtime = runtime,
        text = grammar.getSourceText(ctx),
        grammar.getComment(ctx)
    )
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: WdlV1Parser.Import_aliasContext): ImportAlias = {
    // grammar.notifyParserListeners(ctx)
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    ImportAlias(ids(0), ids(1), grammar.getSourceText(ctx))
  }

  /*
import_as
    : AS Identifier
    ;

 import_doc
	: IMPORT string import_as? (import_alias)*
	;
   */
  override def visitImport_doc(ctx: WdlV1Parser.Import_docContext): ImportDoc = {
    // grammar.notifyParserListeners(ctx)
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
    // We let URL potentially be invalid here - an error will be thrown if we actually
    // try to parse the invalid imported document
    ImportDoc(name,
              aliases,
              opts.getURL(url, mustExist = false),
              grammar.getSourceText(ctx),
              grammar.getComment(ctx))
  }

  /* call_alias
	: AS Identifier
	; */
  override def visitCall_alias(ctx: WdlV1Parser.Call_aliasContext): CallAlias = {
    // grammar.notifyParserListeners(ctx)
    CallAlias(ctx.Identifier().getText, grammar.getSourceText(ctx))
  }

  /* call_input
	: Identifier EQUAL expr
	; */
  override def visitCall_input(ctx: WdlV1Parser.Call_inputContext): CallInput = {
    // grammar.notifyParserListeners(ctx)
    val expr = visitExpr(ctx.expr())
    CallInput(ctx.Identifier().getText, expr, grammar.getSourceText(ctx))
  }

  /* call_inputs
	: INPUT COLON (call_input (COMMA call_input)*)
	; */
  override def visitCall_inputs(ctx: WdlV1Parser.Call_inputsContext): CallInputs = {
    // grammar.notifyParserListeners(ctx)
    val inputs: Vector[CallInput] = ctx
      .call_input()
      .asScala
      .map { x =>
        visitCall_input(x)
      }
      .toVector
    CallInputs(inputs, grammar.getSourceText(ctx))
  }

  /* call_body
	: LBRACE call_inputs? RBRACE
	; */
  override def visitCall_body(ctx: WdlV1Parser.Call_bodyContext): CallInputs = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.call_inputs() == null) {
      CallInputs(Vector.empty, grammar.getSourceText(ctx))
    } else {
      visitCall_inputs(ctx.call_inputs())
    }
  }

  /* call
	: CALL Identifier call_alias?  call_body?
	; */
  override def visitCall(ctx: WdlV1Parser.CallContext): Call = {
    // grammar.notifyParserListeners(ctx)
    val name = ctx.call_name().getText

    val alias: Option[CallAlias] =
      if (ctx.call_alias() == null) {
        None
      } else {
        Some(visitCall_alias(ctx.call_alias()))
      }

    val inputs: Option[CallInputs] =
      if (ctx.call_body() == null) {
        None
      } else {
        Some(visitCall_body(ctx.call_body()))
      }

    Call(name, alias, inputs, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /*
scatter
	: SCATTER LPAREN Identifier In expr RPAREN LBRACE inner_workflow_element* RBRACE
 ; */
  override def visitScatter(ctx: WdlV1Parser.ScatterContext): Scatter = {
    // grammar.notifyParserListeners(ctx)
    val id = ctx.Identifier.getText
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Scatter(id, expr, body, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* conditional
	: IF LPAREN expr RPAREN LBRACE inner_workflow_element* RBRACE
	; */
  override def visitConditional(ctx: WdlV1Parser.ConditionalContext): Conditional = {
    // grammar.notifyParserListeners(ctx)
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Conditional(expr, body, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* workflow_input
	: INPUT LBRACE (any_decls)* RBRACE
	; */
  override def visitWorkflow_input(ctx: WdlV1Parser.Workflow_inputContext): InputSection = {
    // grammar.notifyParserListeners(ctx)
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* workflow_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	;
   */
  override def visitWorkflow_output(ctx: WdlV1Parser.Workflow_outputContext): OutputSection = {
    // grammar.notifyParserListeners(ctx)
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  /* inner_workflow_element
	: bound_decls
	| call
	| scatter
	| conditional
	; */
  override def visitInner_workflow_element(
      ctx: WdlV1Parser.Inner_workflow_elementContext
  ): WorkflowElement = {
    // grammar.notifyParserListeners(ctx)
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
  override def visitWorkflow(ctx: WdlV1Parser.WorkflowContext): Workflow = {
    // grammar.notifyParserListeners(ctx)
    val name = ctx.Identifier().getText
    val elems: Vector[WdlV1Parser.Workflow_elementContext] =
      ctx.workflow_element().asScala.toVector

    val input: Option[InputSection] = atMostOneSection(elems.collect {
      case x: WdlV1Parser.InputContext =>
        visitWorkflow_input(x.workflow_input())
    }, "input", ctx)
    val output: Option[OutputSection] = atMostOneSection(elems.collect {
      case x: WdlV1Parser.OutputContext =>
        visitWorkflow_output(x.workflow_output())
    }, "output", ctx)
    val meta: Option[MetaSection] = atMostOneSection(elems.collect {
      case x: WdlV1Parser.Meta_elementContext =>
        visitMeta(x.meta())
    }, "meta", ctx)
    val parameterMeta: Option[ParameterMetaSection] = atMostOneSection(elems.collect {
      case x: WdlV1Parser.Parameter_meta_elementContext =>
        visitParameter_meta(x.parameter_meta())
    }, "parameter_meta", ctx)
    val wfElems: Vector[WorkflowElement] = elems.collect {
      case x: WdlV1Parser.Inner_elementContext =>
        visitInner_workflow_element(x.inner_workflow_element())
    }

    parameterMeta.foreach(validateParamMeta(_, input, output, ctx))

    Workflow(name,
             input,
             output,
             meta,
             parameterMeta,
             wfElems,
             grammar.getSourceText(ctx),
             grammar.getComment(ctx))
  }

  /*
document_element
	: import_doc
	| struct
	| task
	;
   */
  override def visitDocument_element(ctx: WdlV1Parser.Document_elementContext): DocumentElement = {
    // grammar.notifyParserListeners(ctx)
    visitChildren(ctx).asInstanceOf[DocumentElement]
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: WdlV1Parser.VersionContext): Version = {
    // grammar.notifyParserListeners(ctx)
    if (ctx.RELEASE_VERSION() == null)
      throw new Exception("version not specified")
    val value = ctx.RELEASE_VERSION().getText
    Version(WdlVersion.fromName(value), grammar.getSourceText(ctx))
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  override def visitDocument(ctx: WdlV1Parser.DocumentContext): Document = {
    // grammar.notifyParserListeners(ctx)
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

    Document(version, elems, workflow, grammar.getSourceText(ctx), grammar.getComment(ctx))
  }

  def parseDocument: Document = {
    apply match {
      case d: Document => d
      case _           => throw new Exception("WDL file does not contain a valid document")
    }
  }

  def parseExpr: Expr = {
    apply match {
      case e: Expr => e
      case _       => throw new Exception("Not a Valid expression")
    }
  }

  def parseWdlType: Type = {
    apply match {
      case t: Type => t
      case _       => throw new Exception("Not a valid WDL type")
    }
  }

  def apply: Element = {
    val ctx = grammar.parser.top.document_or_fragment
    val result = if (ctx.document != null) {
      visitDocument(ctx.document)
    } else if (ctx.expr != null) {
      visitExpr(ctx.expr)
    } else if (ctx.wdl_type != null) {
      visitWdl_type(ctx.wdl_type)
    } else {
      throw new Exception(s"No valid document or fragment")
    }
    grammar.verify()
    result
  }
}
