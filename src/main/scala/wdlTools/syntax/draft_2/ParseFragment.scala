package wdlTools.syntax.draft_2

import wdlTools.syntax.{AbstractSyntax, WdlFragmentParser}
import wdlTools.util.Options

case class ParseFragment(opts: Options) extends WdlFragmentParser {
  private val grammarFactory = WdlDraft2GrammarFactory(opts)

  override def parseExpr(text: String): AbstractSyntax.Expr = {
    val parser = ParseOne(opts, grammarFactory.createGrammar(text))
    Translators.translateExpr(parser.parseExpr)
  }

  override def parseType(text: String): AbstractSyntax.Type = {
    val parser = ParseOne(opts, grammarFactory.createGrammar(text))
    Translators.translateType(parser.parseWdlType)
  }
}
