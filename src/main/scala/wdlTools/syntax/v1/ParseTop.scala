package wdlTools.syntax.v1

// Parse one document. Do not follow imports.

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.tree.TerminalNode
import org.openwdl.wdl.parser.v1.{WdlV1Parser, WdlV1ParserBaseVisitor}
import wdlTools.syntax.Antlr4Util.getSourceLocation
import wdlTools.syntax.v1.ConcreteSyntax._
import wdlTools.syntax.{CommentMap, Quoting, SourceLocation, SyntaxException, WdlVersion}

import scala.jdk.CollectionConverters._

case class ParseTop(grammar: WdlV1Grammar) extends WdlV1ParserBaseVisitor[Element] {
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
  override def visitStruct(ctx: WdlV1Parser.StructContext): TypeStruct = {
    val sName = getIdentifierText(ctx.Identifier(), ctx)
    val members: Vector[StructMember] = ctx
      .unbound_decls()
      .asScala
      .map { x =>
        val decl = visitUnbound_decls(x)
        StructMember(decl.name, decl.wdlType)(decl.loc)
      }
      .toVector

    // check that each field appears once
    members.foldLeft(Set.empty[String]) {
      case (names, member) if names.contains(member.name) =>
        throw new SyntaxException(s"struct ${sName} has field ${member.name} defined twice",
                                  getSourceLocation(grammar.docSource, ctx))
      case (names, member) => names + member.name
    }
    TypeStruct(sName, members)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: WdlV1Parser.Map_typeContext): Type = {
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: WdlV1Parser.Array_typeContext): Type = {
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = ctx.PLUS() != null
    TypeArray(t, nonEmpty)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: WdlV1Parser.Pair_typeContext): Type = {
    val lt: Type = visitWdl_type(ctx.wdl_type(0))
    val rt: Type = visitWdl_type(ctx.wdl_type(1))
    TypePair(lt, rt)(getSourceLocation(grammar.docSource, ctx))
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
    if (ctx.array_type() != null) {
      visitArray_type(ctx.array_type())
    } else if (ctx.map_type() != null) {
      visitMap_type(ctx.map_type())
    } else if (ctx.pair_type() != null) {
      visitPair_type(ctx.pair_type())
    } else if (ctx.STRING() != null) {
      TypeString(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.FILE() != null) {
      TypeFile(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.BOOLEAN() != null) {
      TypeBoolean(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.OBJECT() != null) {
      TypeObject(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.INT() != null) {
      TypeInt(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.FLOAT() != null) {
      TypeFloat(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.Identifier() != null) {
      TypeIdentifier(ctx.getText)(getSourceLocation(grammar.docSource, ctx))
    } else {
      throw new SyntaxException("unrecgonized type", getSourceLocation(grammar.docSource, ctx))
    }
  }

  /*
wdl_type
  : (type_base OPTIONAL | type_base)
  ;
   */
  override def visitWdl_type(ctx: WdlV1Parser.Wdl_typeContext): Type = {
    if (ctx.type_base == null) {
      throw new SyntaxException("missing type", getSourceLocation(grammar.docSource, ctx))
    }
    val t = visitType_base(ctx.type_base())
    if (ctx.OPTIONAL() != null) {
      TypeOptional(t)(getSourceLocation(grammar.docSource, ctx))
    } else {
      t
    }
  }

  // EXPRESSIONS

  override def visitNumber(ctx: WdlV1Parser.NumberContext): Expr = {
    if (ctx.IntLiteral() != null) {
      ExprInt(ctx.getText.toLong)(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.FloatLiteral() != null) {
      ExprFloat(ctx.getText.toDouble)(getSourceLocation(grammar.docSource, ctx))
    } else {
      throw new SyntaxException(s"unrecognized number ${ctx.getText}",
                                getSourceLocation(grammar.docSource, ctx))
    }
  }

  private def visitEscapse_sequence(terminalNode: TerminalNode): String = {
    terminalNode.getText.drop(1) match {
      case "t"                                         => "\t"
      case "n"                                         => "\n"
      case "\\"                                        => "\\"
      case "'"                                         => "'"
      case "\""                                        => "\""
      case s if s.startsWith("u") || s.startsWith("U") =>
        // convert unicode escape to unicode character
        new String(Character.toChars(Integer.parseInt(s.drop(1), 16)))
      case s if s.startsWith("x") =>
        // convert escaped hex value to integer
        Integer.parseInt(s.drop(1), 16).toString
      case s if s.length == 3 =>
        Integer.parseInt(s, 8).toString
      case _ =>
        throw new SyntaxException(s"invalid escape sequence: ${terminalNode}",
                                  getSourceLocation(grammar.docSource, terminalNode))
    }
  }

  override def visitString_part(ctx: WdlV1Parser.String_partContext): ExprString = {
    if (ctx.StringPart() != null) {
      ExprString(ctx.StringPart().getText)(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.EscStringPart() != null) {
      ExprString(visitEscapse_sequence(ctx.EscStringPart()))(
          getSourceLocation(grammar.docSource, ctx)
      )
    } else {
      throw new SyntaxException(s"unrecognized string_part ${ctx}",
                                getSourceLocation(grammar.docSource, ctx))
    }
  }

  override def visitString_parts(ctx: WdlV1Parser.String_partsContext): Expr = {
    val parts = ctx.string_part().asScala.map(visitString_part).toVector
    val (strings, locs) = parts.collect {
      case str: ExprString if str.value.nonEmpty => (str.value, str.loc)
    }.unzip
    val loc = if (locs.nonEmpty) {
      SourceLocation.merge(locs)
    } else {
      getSourceLocation(grammar.docSource, ctx)
    }
    ExprString(strings.mkString(""))(loc)
  }

  // These are parts of string interpolation expressions like:
  //
  // ${true="--yes" false="--no" boolean_value}
  // ${default="foo" optional_value}
  // ${sep=", " array_value}
  //
  private sealed trait PlaceHolderPart
  // true="--yes"    false="--no"
  private case class ExprPlaceholderPartEqual(b: Boolean, value: Expr, loc: SourceLocation)
      extends PlaceHolderPart
  // default="foo"
  private case class ExprPlaceholderPartDefault(value: Expr, loc: SourceLocation)
      extends PlaceHolderPart
  // sep=", "
  private case class ExprPlaceholderPartSep(value: Expr, loc: SourceLocation)
      extends PlaceHolderPart

  /* expression_placeholder_option
  : BoolLiteral EQUAL (string | number)
  | DEFAULT EQUAL (string | number)
  | SEP EQUAL (string | number)
  ; */
  private def parse_placeholder_option(
      ctx: WdlV1Parser.Expression_placeholder_optionContext
  ): (String, Expr) = {
    if (ctx.DEFAULTEQUAL() != null) {
      ("default", visitExpr(ctx.expr))
    } else {
      val optionType = if (ctx.BoolLiteral() != null) {
        ctx.BoolLiteral().getText.toLowerCase()
      } else if (ctx.SEPEQUAL() != null) {
        "sep"
      } else {
        throw new SyntaxException(s"unrecognized placeholder option",
                                  getSourceLocation(grammar.docSource, ctx))
      }
      val expr: Expr = if (ctx.string() != null) {
        ctx.string() match {
          case sq: WdlV1Parser.Squote_stringContext => visitSquote_string(sq)
          case dq: WdlV1Parser.Dquote_stringContext => visitDquote_string(dq)
          case other =>
            throw new SyntaxException(s"unrecognized placeholder option value ${other.getClass}",
                                      getSourceLocation(grammar.docSource, ctx))
        }
      } else {
        throw new SyntaxException(s"${optionType} placeholder option value must be a string",
                                  getSourceLocation(grammar.docSource, ctx))
      }
      (optionType, expr)
    }
  }

  // These are full expressions of the same kind
  //
  // ${true="--yes" false="--no" boolean_value}
  // ${default="foo" optional_value}
  // ${sep=", " array_value}
  private def parseEntirePlaceHolderExpression(options: Map[String, Expr],
                                               expr: Expr,
                                               ctx: ParserRuleContext): Expr = {

    if (options.isEmpty) {
      // This is just an expression inside braces
      // ${1}
      // ${x + 3}
      expr
    } else {
      val loc = getSourceLocation(grammar.docSource, ctx)
      val placeholder = ExprPlaceholder(options.get("true"),
                                        options.get("false"),
                                        options.get("sep"),
                                        options.get("default"),
                                        expr)(loc)
      // according to the spec, only one of true/false, sep, or default is allowed; however,
      // some "industry standard" workflows are not spec compliant and mix default with either
      // sep or true/false, so we are compelled to allow it
      if (placeholder.trueOpt.isDefined != placeholder.falseOpt.isDefined ||
          (placeholder.trueOpt.isDefined && placeholder.sepOpt.isDefined)) {
        throw new SyntaxException("invalid place holder", getSourceLocation(grammar.docSource, ctx))
      }
      placeholder
    }
  }

  /* string_expr_part
  : StringCommandStart (expression_placeholder_option)* expr RBRACE
  ; */
  override def visitString_expr_part(ctx: WdlV1Parser.String_expr_partContext): Expr = {
    val options: Map[String, Expr] = ctx
      .expression_placeholder_option()
      .asScala
      .map(parse_placeholder_option)
      .toMap
    val expr = visitExpr(ctx.expr())
    parseEntirePlaceHolderExpression(options, expr, ctx)
  }

  /* string_expr_with_string_part
  : string_expr_part string_part
  ; */
  override def visitString_expr_with_string_part(
      ctx: WdlV1Parser.String_expr_with_string_partContext
  ): ExprCompoundString = {
    val exprPart = visitString_expr_part(ctx.string_expr_part())
    val stringPart = visitString_parts(ctx.string_parts())
    val loc = getSourceLocation(grammar.docSource, ctx)
    (exprPart, stringPart) match {
      case (e, ExprString(s, _)) if s.isEmpty => ExprCompoundString(Vector(e))(loc)
      case (e, s)                             => ExprCompoundString(Vector(e, s))(loc)
    }
  }

  /*
string
  : DQUOTE string_part string_expr_with_string_part* DQUOTE
  | SQUOTE string_part string_expr_with_string_part* SQUOTE
  ;
   */
  private def visitString(quoting: Quoting.Quoting,
                          stringParts: WdlV1Parser.String_partsContext,
                          stringExprWithStringPart: scala.collection.Seq[
                              WdlV1Parser.String_expr_with_string_partContext
                          ],
                          ctx: WdlV1Parser.StringContext): Expr = {
    val stringPart = visitString_parts(stringParts)
    val exprPart: Vector[Expr] = stringExprWithStringPart
      .map(visitString_expr_with_string_part)
      .toVector
      .flatMap(_.value)
    (stringPart, exprPart) match {
      case (s: ExprString, Vector()) => s.copy(quoting = quoting)(s.loc)
      case (ExprString(s, _), parts) if s.isEmpty =>
        ExprCompoundString(parts, quoting = quoting)(getSourceLocation(grammar.docSource, ctx))
      case (s, parts) =>
        ExprCompoundString(s +: parts, quoting = quoting)(getSourceLocation(grammar.docSource, ctx))
    }
  }

  override def visitSquote_string(ctx: WdlV1Parser.Squote_stringContext): Expr = {
    visitString(Quoting.Single, ctx.string_parts(), ctx.string_expr_with_string_part().asScala, ctx)
  }

  override def visitDquote_string(ctx: WdlV1Parser.Dquote_stringContext): Expr = {
    visitString(Quoting.Double, ctx.string_parts(), ctx.string_expr_with_string_part().asScala, ctx)
  }

  /* primitive_literal
    : BoolLiteral
    | number
    | string
    | Identifier
    ; */
  override def visitPrimitive_literal(ctx: WdlV1Parser.Primitive_literalContext): Expr = {
    if (ctx.BoolLiteral() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      ExprBoolean(value)(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.number() != null) {
      visitNumber(ctx.number())
    } else if (ctx.string() != null) {
      ctx.string() match {
        case sq: WdlV1Parser.Squote_stringContext => visitSquote_string(sq)
        case dq: WdlV1Parser.Dquote_stringContext => visitDquote_string(dq)
      }
    } else if (ctx.Identifier() != null) {
      ExprIdentifier(ctx.getText)(getSourceLocation(grammar.docSource, ctx))
    } else {
      throw new SyntaxException("unrecognized primitive_literal",
                                getSourceLocation(grammar.docSource, ctx))
    }
  }

  override def visitLor(ctx: WdlV1Parser.LorContext): Expr = {
    val arg0: Expr = visitExpr_infix0(ctx.expr_infix0())
    val arg1: Expr = visitExpr_infix1(ctx.expr_infix1())
    ExprLor(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLand(ctx: WdlV1Parser.LandContext): Expr = {
    val arg0 = visitExpr_infix1(ctx.expr_infix1())
    val arg1 = visitExpr_infix2(ctx.expr_infix2())
    ExprLand(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitEqeq(ctx: WdlV1Parser.EqeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprEqeq(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLt(ctx: WdlV1Parser.LtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLt(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitGte(ctx: WdlV1Parser.GteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGte(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitNeq(ctx: WdlV1Parser.NeqContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprNeq(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLte(ctx: WdlV1Parser.LteContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprLte(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitGt(ctx: WdlV1Parser.GtContext): Expr = {
    val arg0 = visitExpr_infix2(ctx.expr_infix2())
    val arg1 = visitExpr_infix3(ctx.expr_infix3())
    ExprGt(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitAdd(ctx: WdlV1Parser.AddContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprAdd(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitSub(ctx: WdlV1Parser.SubContext): Expr = {
    val arg0 = visitExpr_infix3(ctx.expr_infix3())
    val arg1 = visitExpr_infix4(ctx.expr_infix4())
    ExprSub(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitMod(ctx: WdlV1Parser.ModContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMod(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitMul(ctx: WdlV1Parser.MulContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprMul(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitDivide(ctx: WdlV1Parser.DivideContext): Expr = {
    val arg0 = visitExpr_infix4(ctx.expr_infix4())
    val arg1 = visitExpr_infix5(ctx.expr_infix5())
    ExprDivide(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
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
    ExprArrayLiteral(elements)(getSourceLocation(grammar.docSource, ctx))
  }

  // | LPAREN expr COMMA expr RPAREN #pair_literal
  override def visitPair_literal(ctx: WdlV1Parser.Pair_literalContext): Expr = {
    val arg0 = visitExpr(ctx.expr(0))
    val arg1 = visitExpr(ctx.expr(1))
    ExprPair(arg0, arg1)(getSourceLocation(grammar.docSource, ctx))
  }

  //| LBRACE (expr COLON expr (COMMA expr COLON expr)*)* RBRACE #map_literal
  override def visitMap_literal(ctx: WdlV1Parser.Map_literalContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector

    val n = elements.size
    if (n % 2 != 0) {
      throw new SyntaxException("the expressions in a map must come in pairs",
                                getSourceLocation(grammar.docSource, ctx))
    }

    val m: Vector[ExprMember] = Vector.tabulate(n / 2) { i =>
      val key = elements(2 * i)
      val value = elements(2 * i + 1)
      ExprMember(key, value)(
          SourceLocation(grammar.docSource,
                         key.loc.line,
                         key.loc.col,
                         value.loc.endLine,
                         value.loc.endCol)
      )
    }
    ExprMapLiteral(m)(getSourceLocation(grammar.docSource, ctx))
  }

  // | OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
  override def visitObject_literal(ctx: WdlV1Parser.Object_literalContext): Expr = {
    val ids: Vector[Expr] = ctx
      .member()
      .asScala
      .toVector
      .map { m =>
        ExprString(getIdentifierText(m.Identifier(), ctx))(getSourceLocation(grammar.docSource, m))
      }
    val elements: Vector[Expr] = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    val members = ids.zip(elements).map { pair =>
      val id = pair._1
      val expr = pair._2
      val loc = SourceLocation(grammar.docSource,
                               id.loc.line,
                               id.loc.col,
                               expr.loc.endLine,
                               expr.loc.endCol)
      ExprMember(id, expr)(loc)
    }
    ExprObjectLiteral(members)(getSourceLocation(grammar.docSource, ctx))
  }

  // | NOT expr #negate
  override def visitNegate(ctx: WdlV1Parser.NegateContext): Expr = {
    val expr = visitExpr_infix5(ctx.expr_infix5())
    // simplify boolean literals
    expr match {
      case b: ExprBoolean => ExprBoolean(!b.value)(b.loc)
      case _              => ExprNegate(expr)(getSourceLocation(grammar.docSource, ctx))
    }
  }

  // | (PLUS | MINUS) expr #unarysigned
  override def visitUnarysigned(ctx: WdlV1Parser.UnarysignedContext): Expr = {
    val expr = visitExpr_infix5(ctx.expr_infix5())
    // simplify positive/negative numeric literals
    if (ctx.PLUS() != null) {
      expr match {
        case i: ExprInt   => i
        case f: ExprFloat => f
        case _            => ExprUnaryPlus(expr)(getSourceLocation(grammar.docSource, ctx))
      }
    } else if (ctx.MINUS() != null) {
      expr match {
        case i: ExprInt   => ExprInt(-i.value)(i.loc)
        case f: ExprFloat => ExprFloat(-f.value)(f.loc)
        case _            => ExprUnaryMinus(expr)(getSourceLocation(grammar.docSource, ctx))
      }
    } else {
      throw new SyntaxException("bad unary expression", getSourceLocation(grammar.docSource, ctx))
    }
  }

  // | expr_core LBRACK expr RBRACK #at
  override def visitAt(ctx: WdlV1Parser.AtContext): Expr = {
    val array = visitExpr_core(ctx.expr_core())
    val index = visitExpr(ctx.expr())
    ExprAt(array, index)(getSourceLocation(grammar.docSource, ctx))
  }

  // | Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
  override def visitApply(ctx: WdlV1Parser.ApplyContext): Expr = {
    val funcName = getIdentifierText(ctx.Identifier(), ctx)
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprApply(funcName, elements)(getSourceLocation(grammar.docSource, ctx))
  }

  // | IF expr THEN expr ELSE expr #ifthenelse
  override def visitIfthenelse(ctx: WdlV1Parser.IfthenelseContext): Expr = {
    val elements = ctx
      .expr()
      .asScala
      .map(x => visitExpr(x))
      .toVector
    ExprIfThenElse(elements(0), elements(1), elements(2))(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitLeft_name(ctx: WdlV1Parser.Left_nameContext): Expr = {
    val id = getIdentifierText(ctx.Identifier(), ctx)
    ExprIdentifier(id)(getSourceLocation(grammar.docSource, ctx))
  }

  // | expr_core DOT Identifier #get_name
  override def visitGet_name(ctx: WdlV1Parser.Get_nameContext): Expr = {
    val e = visitExpr_core(ctx.expr_core())
    val id = ctx.Identifier.getText
    ExprGetName(e, id)(getSourceLocation(grammar.docSource, ctx))
  }

  /*expr_infix0
	: expr_infix0 OR expr_infix1 #lor
	| expr_infix1 #infix1
	; */

  private def visitExpr_infix0(ctx: WdlV1Parser.Expr_infix0Context): Expr = {
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
  private def visitExpr_infix5(ctx: WdlV1Parser.Expr_infix5Context): Expr = {
    ctx match {
      case negate: WdlV1Parser.NegateContext           => visitNegate(negate)
      case unarysigned: WdlV1Parser.UnarysignedContext => visitUnarysigned(unarysigned)
      case infix6: WdlV1Parser.Infix6Context           => visitInfix6(infix6).asInstanceOf[Expr]
    }
  }

  override def visitExpr_infix6(ctx: WdlV1Parser.Expr_infix6Context): Expr = {
    visitExpr_core(ctx.expr_core())
  }

  /* expr
	: expr_infix
	; */
  override def visitExpr(ctx: WdlV1Parser.ExprContext): Expr = {
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
	| OBJECT_LITERAL LBRACE (Identifier COLON expr (COMMA Identifier COLON expr)*)* RBRACE #object_literal
	| NOT expr #negate
	| (PLUS | MINUS) expr #unarysigned
	| expr_core LBRACK expr RBRACK #at
	| IF expr THEN expr ELSE expr #ifthenelse
	| Identifier LPAREN (expr (COMMA expr)*)? RPAREN #apply
	| Identifier #left_name
	| expr_core DOT Identifier #get_name
	; */
  private def visitExpr_core(ctx: WdlV1Parser.Expr_coreContext): Expr = {
    ctx match {
      case group: WdlV1Parser.Expression_groupContext => visitExpression_group(group)
      case primitives: WdlV1Parser.PrimitivesContext =>
        visitPrimitive_literal(primitives.primitive_literal())
      case array_literal: WdlV1Parser.Array_literalContext => visitArray_literal(array_literal)
      case pair_literal: WdlV1Parser.Pair_literalContext   => visitPair_literal(pair_literal)
      case map_literal: WdlV1Parser.Map_literalContext     => visitMap_literal(map_literal)
      case obj_literal: WdlV1Parser.Object_literalContext  => visitObject_literal(obj_literal)
      case at: WdlV1Parser.AtContext                       => visitAt(at)
      case ifthenelse: WdlV1Parser.IfthenelseContext       => visitIfthenelse(ifthenelse)
      case apply: WdlV1Parser.ApplyContext                 => visitApply(apply)
      case left_name: WdlV1Parser.Left_nameContext         => visitLeft_name(left_name)
      case get_name: WdlV1Parser.Get_nameContext           => visitGet_name(get_name)
      case _ =>
        throw new SyntaxException("bad expression", getSourceLocation(grammar.docSource, ctx))
    }
  }

  /*
unbound_decls
	: wdl_type Identifier
	;
   */
  override def visitUnbound_decls(ctx: WdlV1Parser.Unbound_declsContext): Declaration = {
    if (ctx.wdl_type() == null) {
      throw new SyntaxException("missing type", getSourceLocation(grammar.docSource, ctx))
    }
    val wdlType: Type = visitWdl_type(ctx.wdl_type())
    val name: String = getIdentifierText(ctx.Identifier(), ctx)
    Declaration(name, wdlType, None)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
bound_decls
	: wdl_type Identifier EQUAL expr
	;
   */
  override def visitBound_decls(ctx: WdlV1Parser.Bound_declsContext): Declaration = {
    if (ctx.wdl_type() == null) {
      throw new SyntaxException("missing type", getSourceLocation(grammar.docSource, ctx))
    }
    val wdlType = visitWdl_type(ctx.wdl_type())
    val name: String = getIdentifierText(ctx.Identifier(), ctx)
    if (ctx.expr() == null) {
      Declaration(name, wdlType, None)(getSourceLocation(grammar.docSource, ctx))
    } else {
      val expr: Expr = visitExpr(ctx.expr())
      val fixedExpr = (wdlType, expr) match {
        case (_: TypeStruct | _: TypeIdentifier, e @ ExprMapLiteral(members)) =>
          // automatically fix assigning Map value to struct type
          ExprObjectLiteral(members)(e.loc)
        case _ => expr
      }
      Declaration(name, wdlType, Some(fixedExpr))(getSourceLocation(grammar.docSource, ctx))
    }
  }

  /*
any_decls
	: unbound_decls
	| bound_decls
	;
   */
  override def visitAny_decls(ctx: WdlV1Parser.Any_declsContext): Declaration = {
    if (ctx.unbound_decls() != null) {
      visitUnbound_decls(ctx.unbound_decls())
    } else if (ctx.bound_decls() != null) {
      visitBound_decls(ctx.bound_decls())
    } else {
      throw new SyntaxException("unrecognized declaration",
                                getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* meta_value
    : BoolLiteral
    | number
    | string
    | meta_object
    | meta_array
    | NULL_LITERAL
    ; */
  override def visitMeta_value(ctx: WdlV1Parser.Meta_valueContext): MetaValue = {
    if (ctx.MetaNull() != null) {
      MetaValueNull(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.MetaBool() != null) {
      val value = ctx.getText.toLowerCase() == "true"
      MetaValueBoolean(value)(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.MetaInt() != null) {
      MetaValueInt(ctx.MetaInt().getText.toLong)(getSourceLocation(grammar.docSource, ctx))
    } else if (ctx.MetaFloat() != null) {
      MetaValueFloat(ctx.MetaFloat().getText.toDouble)(
          getSourceLocation(grammar.docSource, ctx)
      )
    } else if (ctx.meta_string() != null) {
      ctx.meta_string() match {
        case sq: WdlV1Parser.Meta_squote_stringContext => visitMeta_squote_string(sq)
        case dq: WdlV1Parser.Meta_dquote_stringContext => visitMeta_dquote_string(dq)
      }
    } else if (ctx.meta_array() != null) {
      visitMeta_array(ctx.meta_array())
    } else if (ctx.meta_object() != null) {
      visitMeta_object(ctx.meta_object())
    } else {
      throw new SyntaxException("unrecognized meta_value",
                                getSourceLocation(grammar.docSource, ctx))
    }
  }

  override def visitMeta_string_part(ctx: WdlV1Parser.Meta_string_partContext): MetaValueString = {
    val text = if (ctx.MetaStringPart() != null) {
      ctx.MetaStringPart().getText
    } else if (ctx.MetaEscStringPart() != null) {
      visitEscapse_sequence(ctx.MetaEscStringPart())
    } else {
      throw new SyntaxException(s"invalid meta_string_part ${ctx}",
                                getSourceLocation(grammar.docSource, ctx))
    }
    MetaValueString(text)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitMeta_string_parts(
      ctx: WdlV1Parser.Meta_string_partsContext
  ): MetaValueString = {
    MetaValueString(
        ctx
          .meta_string_part()
          .asScala
          .map(visitMeta_string_part)
          .map(_.value)
          .mkString
    )(
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  override def visitMeta_squote_string(
      ctx: WdlV1Parser.Meta_squote_stringContext
  ): MetaValueString = {
    val s = visitMeta_string_parts(ctx.meta_string_parts())
    s.copy(quoting = Quoting.Single)(s.loc)
  }

  override def visitMeta_dquote_string(
      ctx: WdlV1Parser.Meta_dquote_stringContext
  ): MetaValueString = {
    val s = visitMeta_string_parts(ctx.meta_string_parts())
    s.copy(quoting = Quoting.Double)(s.loc)
  }

  /* meta_array: LBRACK (meta_value (COMMA meta_value)*)* RBRACK;
   */
  override def visitMeta_array(ctx: WdlV1Parser.Meta_arrayContext): MetaValueArray = {
    val items = if (ctx.MetaEmptyArray() != null) {
      Vector.empty
    } else {
      ctx.meta_value().asScala.toVector.map(visitMeta_value)
    }
    MetaValueArray(items)(getSourceLocation(grammar.docSource, ctx))
  }

  private def visitMeta_kv(identifier: TerminalNode,
                           meta_value: WdlV1Parser.Meta_valueContext,
                           parent: ParserRuleContext) = {
    if (identifier == null) {
      throw new SyntaxException("missing identifier", getSourceLocation(grammar.docSource, parent))
    }
    val id = getIdentifierText(identifier, parent)
    if (meta_value == null) {
      throw new SyntaxException(s"Invalid expression for meta key ${id}",
                                getSourceLocation(grammar.docSource, parent))
    }
    val value = visitMeta_value(meta_value)
    MetaKV(id, value)(getSourceLocation(grammar.docSource, parent))
  }

  /* meta_object: LBRACE (meta_kv (COMMA meta_kv)*)* RBRACE;
   */
  override def visitMeta_object(ctx: WdlV1Parser.Meta_objectContext): MetaValueObject = {
    val members = ctx
      .meta_object_kv()
      .asScala
      .toVector
      .map(member => visitMeta_kv(member.MetaObjectIdentifier(), member.meta_value(), member))
    MetaValueObject(members)(getSourceLocation(grammar.docSource, ctx))
  }

  /* meta_kv
     : Identifier COLON expr
     ; */
  override def visitMeta_kv(ctx: WdlV1Parser.Meta_kvContext): MetaKV = {
    visitMeta_kv(ctx.MetaIdentifier(), ctx.meta_value(), ctx)
  }

  //  PARAMETERMETA LBRACE meta_kv* RBRACE #parameter_meta
  override def visitParameter_meta(
      ctx: WdlV1Parser.Parameter_metaContext
  ): ParameterMetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    ParameterMetaSection(kvs)(getSourceLocation(grammar.docSource, ctx))
  }

  //  META LBRACE meta_kv* RBRACE #meta
  override def visitMeta(ctx: WdlV1Parser.MetaContext): MetaSection = {
    val kvs: Vector[MetaKV] = ctx
      .meta_kv()
      .asScala
      .map(x => visitMeta_kv(x))
      .toVector
    MetaSection(kvs)(getSourceLocation(grammar.docSource, ctx))
  }

  /* task_runtime_kv
 : Identifier COLON expr
 ; */
  override def visitTask_runtime_kv(ctx: WdlV1Parser.Task_runtime_kvContext): RuntimeKV = {
    val id: String = getIdentifierText(ctx.Identifier(), ctx)
    val expr: Expr = visitExpr(ctx.expr())
    RuntimeKV(id, expr)(getSourceLocation(grammar.docSource, ctx))
  }

  /* task_runtime
 : RUNTIME LBRACE (task_runtime_kv)* RBRACE
 ; */
  override def visitTask_runtime(ctx: WdlV1Parser.Task_runtimeContext): RuntimeSection = {
    val kvs = ctx
      .task_runtime_kv()
      .asScala
      .map(x => visitTask_runtime_kv(x))
      .toVector
    RuntimeSection(kvs)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
task_input
	: INPUT LBRACE (any_decls)* RBRACE
	;
   */
  override def visitTask_input(ctx: WdlV1Parser.Task_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls)(getSourceLocation(grammar.docSource, ctx))
  }

  /* task_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	; */
  override def visitTask_output(ctx: WdlV1Parser.Task_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls)(getSourceLocation(grammar.docSource, ctx))
  }

  override def visitTask_command_string_parts(
      ctx: WdlV1Parser.Task_command_string_partsContext
  ): ExprString = {
    val text = ctx
      .CommandStringPart()
      .asScala
      .map(_.getText)
      .mkString("")
    ExprString(text)(getSourceLocation(grammar.docSource, ctx))
  }

  /* task_command_expr_part
    : StringCommandStart  (expression_placeholder_option)* expr RBRACE
    ; */
  override def visitTask_command_expr_part(
      ctx: WdlV1Parser.Task_command_expr_partContext
  ): Expr = {
    val options: Map[String, Expr] = ctx
      .expression_placeholder_option()
      .asScala
      .map(x => parse_placeholder_option(x))
      .toMap
    val expr = visitExpr(ctx.expr())
    parseEntirePlaceHolderExpression(options, expr, ctx)
  }

  /* task_command_expr_with_string
    : task_command_expr_part task_command_string_part
    ; */
  override def visitTask_command_expr_with_string(
      ctx: WdlV1Parser.Task_command_expr_with_stringContext
  ): Expr = {
    val exprPart: Expr = visitTask_command_expr_part(ctx.task_command_expr_part())
    val stringPart: Expr = visitTask_command_string_parts(
        ctx.task_command_string_parts()
    )
    (exprPart, stringPart) match {
      case (e, ExprString(s, Quoting.None)) if s.isEmpty => e
      case (ExprString(e, Quoting.None), s) if e.isEmpty => s
      case (e, s) =>
        ExprCompoundString(Vector(e, s))(getSourceLocation(grammar.docSource, ctx))
    }
  }

  /* task_command
  : COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  | HEREDOC_COMMAND task_command_string_part task_command_expr_with_string* EndCommand
  ; */
  override def visitTask_command(ctx: WdlV1Parser.Task_commandContext): CommandSection = {
    val start: Expr = visitTask_command_string_parts(ctx.task_command_string_parts())
    val parts: Vector[Expr] = ctx
      .task_command_expr_with_string()
      .asScala
      .map(x => visitTask_command_expr_with_string(x))
      .toVector
    // discard empty strings, and flatten compound vectors of strings
    val cleanedParts = (start +: parts).flatMap {
      case ExprString(x, _) if x.isEmpty       => Vector.empty
      case ExprCompoundString(v, Quoting.None) => v
      case other                               => Vector(other)
    }
    CommandSection(cleanedParts)(getSourceLocation(grammar.docSource, ctx))
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

  /* task
	: TASK Identifier LBRACE (task_element)+ RBRACE
	;  */
  override def visitTask(ctx: WdlV1Parser.TaskContext): Task = {
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

    Task(name, input, output, command, decls, meta, parameterMeta, runtime)(
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  def visitImport_addr(ctx: WdlV1Parser.StringContext): ImportAddr = {
    val addr = ctx.getText.replaceAll("\"", "")
    ImportAddr(addr)(getSourceLocation(grammar.docSource, ctx))
  }

  /* import_alias
	: ALIAS Identifier AS Identifier
	;*/
  override def visitImport_alias(ctx: WdlV1Parser.Import_aliasContext): ImportAlias = {
    val ids = ctx
      .Identifier()
      .asScala
      .map(x => x.getText)
      .toVector
    ImportAlias(ids(0), ids(1))(getSourceLocation(grammar.docSource, ctx))
  }

  /*
    import_as
        : AS Identifier
        ;
   */
  override def visitImport_as(ctx: WdlV1Parser.Import_asContext): ImportName = {
    ImportName(ctx.Identifier().getText)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
 import_doc
	: IMPORT string import_as? (import_alias)*
	;
   */
  override def visitImport_doc(ctx: WdlV1Parser.Import_docContext): ImportDoc = {
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
    ImportDoc(name, aliases, addr)(getSourceLocation(grammar.docSource, ctx))
  }

  /* call_alias
	: AS Identifier
	; */
  override def visitCall_alias(ctx: WdlV1Parser.Call_aliasContext): CallAlias = {
    CallAlias(getIdentifierText(ctx.Identifier(), ctx))(getSourceLocation(grammar.docSource, ctx))
  }

  /* call_input
	: Identifier EQUAL expr
	; */
  override def visitCall_input(ctx: WdlV1Parser.Call_inputContext): CallInput = {
    val expr = visitExpr(ctx.expr())
    CallInput(getIdentifierText(ctx.Identifier(), ctx), expr)(
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  /* call_inputs
	: INPUT COLON (call_input (COMMA call_input)*)
	; */
  override def visitCall_inputs(ctx: WdlV1Parser.Call_inputsContext): CallInputs = {
    val inputs: Vector[CallInput] = ctx
      .call_input()
      .asScala
      .map { x =>
        visitCall_input(x)
      }
      .toVector
    CallInputs(inputs)(getSourceLocation(grammar.docSource, ctx))
  }

  /* call_body
	: LBRACE call_inputs? RBRACE
	; */
  override def visitCall_body(ctx: WdlV1Parser.Call_bodyContext): CallInputs = {
    if (ctx.call_inputs() == null) {
      CallInputs(Vector.empty)(getSourceLocation(grammar.docSource, ctx))
    } else {
      visitCall_inputs(ctx.call_inputs())
    }
  }

  /* call
	: CALL Identifier call_alias?  call_body?
	; */
  override def visitCall(ctx: WdlV1Parser.CallContext): Call = {
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

    Call(name, alias, inputs)(getSourceLocation(grammar.docSource, ctx))
  }

  /*
scatter
	: SCATTER LPAREN Identifier In expr RPAREN LBRACE inner_workflow_element* RBRACE
 ; */
  override def visitScatter(ctx: WdlV1Parser.ScatterContext): Scatter = {
    val id = getIdentifierText(ctx.Identifier(), ctx)
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Scatter(id, expr, body)(getSourceLocation(grammar.docSource, ctx))
  }

  /* conditional
	: IF LPAREN expr RPAREN LBRACE inner_workflow_element* RBRACE
	; */
  override def visitConditional(ctx: WdlV1Parser.ConditionalContext): Conditional = {
    val expr = visitExpr(ctx.expr())
    val body = ctx
      .inner_workflow_element()
      .asScala
      .map(visitInner_workflow_element)
      .toVector
    Conditional(expr, body)(getSourceLocation(grammar.docSource, ctx))
  }

  /* workflow_input
	: INPUT LBRACE (any_decls)* RBRACE
	; */
  override def visitWorkflow_input(ctx: WdlV1Parser.Workflow_inputContext): InputSection = {
    val decls = ctx
      .any_decls()
      .asScala
      .map(x => visitAny_decls(x))
      .toVector
    InputSection(decls)(getSourceLocation(grammar.docSource, ctx))
  }

  /* workflow_output
	: OUTPUT LBRACE (bound_decls)* RBRACE
	;
   */
  override def visitWorkflow_output(ctx: WdlV1Parser.Workflow_outputContext): OutputSection = {
    val decls = ctx
      .bound_decls()
      .asScala
      .map(x => visitBound_decls(x))
      .toVector
    OutputSection(decls)(getSourceLocation(grammar.docSource, ctx))
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
    if (ctx.bound_decls() != null) {
      visitBound_decls(ctx.bound_decls())
    } else if (ctx.call() != null) {
      visitCall(ctx.call())
    } else if (ctx.scatter() != null) {
      visitScatter(ctx.scatter())
    } else if (ctx.conditional() != null) {
      visitConditional(ctx.conditional())
    } else {
      throw new Exception("unrecognized workflow element")
    }
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
    val name = getIdentifierText(ctx.Identifier(), ctx)
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

    Workflow(name, input, output, meta, parameterMeta, wfElems)(
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  /*
document_element
	: import_doc
	| struct
	| task
	;
   */
  override def visitDocument_element(ctx: WdlV1Parser.Document_elementContext): DocumentElement = {
    visitChildren(ctx).asInstanceOf[DocumentElement]
  }

  /* version
	: VERSION RELEASE_VERSION
	; */
  override def visitVersion(ctx: WdlV1Parser.VersionContext): Version = {
    if (ctx.ReleaseVersion() == null) {
      throw new SyntaxException("unrecognized version", getSourceLocation(grammar.docSource, ctx))
    }
    val value = ctx.ReleaseVersion().getText
    Version(WdlVersion.withName(value))(getSourceLocation(grammar.docSource, ctx))
  }

  /*
document
	: version document_element* (workflow document_element*)?
	;
   */
  def visitDocument(ctx: WdlV1Parser.DocumentContext, comments: CommentMap): Document = {
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

    Document(grammar.docSource, version, elems, workflow, comments)(
        getSourceLocation(grammar.docSource, ctx)
    )
  }

  def visitExprDocument(ctx: WdlV1Parser.Expr_documentContext): Expr = {
    visitExpr(ctx.expr())
  }

  def visitTypeDocument(ctx: WdlV1Parser.Type_documentContext): Type = {
    visitWdl_type(ctx.wdl_type())
  }

  def parseDocument: Document = {
    grammar
      .visitDocument[WdlV1Parser.DocumentContext, Document](grammar.parser.document, visitDocument)
  }

  def parseExpr: Expr = {
    grammar.visitFragment[WdlV1Parser.Expr_documentContext, Expr](grammar.parser.expr_document,
                                                                  visitExprDocument)
  }

  def parseWdlType: Type = {
    grammar.visitFragment[WdlV1Parser.Type_documentContext, Type](grammar.parser.type_document,
                                                                  visitTypeDocument)
  }
}
