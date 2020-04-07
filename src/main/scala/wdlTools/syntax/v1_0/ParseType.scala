package wdlTools.syntax.v1_0

import wdlTools.syntax.{AbstractSyntax, WdlTypeParser}
import wdlTools.syntax.v1_0.ParseTypeDocument.V1_0TypeGrammarFactory
import wdlTools.util.Options

case class ParseType(opts: Options) extends WdlTypeParser {
  def apply(text: String): AbstractSyntax.Type = {
    val grammarFactory = V1_0TypeGrammarFactory(opts)
    val grammar = grammarFactory.createGrammar(text)
    val visitor = new ParseTypeDocument(grammar, opts)
    val document = visitor.apply()
    grammar.verify()
    document
  }
}
