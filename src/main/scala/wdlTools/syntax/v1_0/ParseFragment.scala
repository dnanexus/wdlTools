package wdlTools.syntax.v1_0

import wdlTools.syntax.{AbstractSyntax, WdlFragmentParser}
import wdlTools.util.Options

case class ParseFragment(opts: Options) extends WdlFragmentParser {
  private val grammarFactory = WdlV1GrammarFactory(opts)

  override def parseExpr(text: String): AbstractSyntax.Expr = {
    val parser = ParseTop(opts, grammarFactory.createGrammar(text))
    Translators.translateExpr(parser.parseExpr)
  }

  override def parseType(text: String): AbstractSyntax.Type = {
    val parser = ParseTop(opts, grammarFactory.createGrammar(text))
    Translators.translateType(parser.parseWdlType)
  }
}
