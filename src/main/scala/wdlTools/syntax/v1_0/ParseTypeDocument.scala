package wdlTools.syntax.v1_0

import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.{CharStream, CommonTokenStream, ParserRuleContext}
import org.openwdl.wdl.parser.v1_0.{V10WdlLexer, V10WdlTypeParser, V10WdlTypeParserBaseVisitor}
import wdlTools.syntax.Antlr4Util.{Grammar, GrammarFactory}
import wdlTools.syntax.v1_0.ConcreteSyntax._
import wdlTools.syntax.{AbstractSyntax, TextSource}
import wdlTools.util.Options

object ParseTypeDocument {
  case class V1_0TypeGrammarFactory(opts: Options)
      extends GrammarFactory[V10WdlLexer, V10WdlTypeParser](opts) {
    override def createLexer(charStream: CharStream): V10WdlLexer = {
      new V10WdlLexer(charStream)
    }

    override def createParser(tokenStream: CommonTokenStream): V10WdlTypeParser = {
      new V10WdlTypeParser(tokenStream)
    }
  }
}

case class ParseTypeDocument(grammar: Grammar[V10WdlLexer, V10WdlTypeParser], opts: Options)
    extends V10WdlTypeParserBaseVisitor[Element] {
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
map_type
	: MAP LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitMap_type(ctx: V10WdlTypeParser.Map_typeContext): Type = {
    val kt: Type = visitWdl_type(ctx.wdl_type(0))
    val vt: Type = visitWdl_type(ctx.wdl_type(1))
    TypeMap(kt, vt, getSourceText(ctx))
  }

  /*
array_type
	: ARRAY LBRACK wdl_type RBRACK PLUS?
	;
   */
  override def visitArray_type(ctx: V10WdlTypeParser.Array_typeContext): Type = {
    val t: Type = visitWdl_type(ctx.wdl_type())
    val nonEmpty = ctx.PLUS() != null
    TypeArray(t, nonEmpty, getSourceText(ctx))
  }

  /*
pair_type
	: PAIR LBRACK wdl_type COMMA wdl_type RBRACK
	;
   */
  override def visitPair_type(ctx: V10WdlTypeParser.Pair_typeContext): Type = {
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
  override def visitType_base(ctx: V10WdlTypeParser.Type_baseContext): Type = {
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
  override def visitWdl_type(ctx: V10WdlTypeParser.Wdl_typeContext): Type = {
    visitChildren(ctx).asInstanceOf[Type]
  }

  /*
document
: version document_element* (workflow document_element*)?
;
   */
  override def visitDocument(ctx: V10WdlTypeParser.DocumentContext): Type = {
    visitWdl_type(ctx.wdl_type())
  }

  def apply(): AbstractSyntax.Type = {
    val concreteType = visitDocument(grammar.parser.document)
    Translators.translateType(concreteType)
  }
}
