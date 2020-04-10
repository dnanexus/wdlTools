package wdlTools.syntax.v1_0

import wdlTools.syntax.v1_0.ParseExprDocument.V1_0ExprGrammarFactory
import wdlTools.syntax.{AbstractSyntax, WdlExprParser}
import wdlTools.util.Options

case class ParseExpr(opts: Options) extends WdlExprParser {
  def apply(text: String): AbstractSyntax.Expr = {
    val grammarFactory = V1_0ExprGrammarFactory(opts)
    val grammar = grammarFactory.createGrammar(text)
    val visitor = new ParseExprDocument(grammar, opts)
    val document = visitor.apply()
    grammar.verify()
    document
  }
}