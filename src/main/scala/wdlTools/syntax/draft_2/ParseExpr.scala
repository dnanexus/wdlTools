package wdlTools.syntax.draft_2

import wdlTools.syntax.draft_2.ParseExprDocument.Draft2ExprGrammarFactory
import wdlTools.syntax.{AbstractSyntax, WdlExprParser}
import wdlTools.util.Options

case class ParseExpr(opts: Options) extends WdlExprParser {
  def apply(text: String): AbstractSyntax.Expr = {
    val grammarFactory = Draft2ExprGrammarFactory(opts)
    val grammar = grammarFactory.createGrammar(text)
    val visitor = new ParseExprDocument(grammar, opts)
    val document = visitor.apply()
    grammar.verify()
    document
  }
}
