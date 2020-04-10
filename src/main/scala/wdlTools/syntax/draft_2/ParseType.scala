package wdlTools.syntax.draft_2

import wdlTools.syntax.draft_2.ParseTypeDocument.Draft2TypeGrammarFactory
import wdlTools.syntax.{AbstractSyntax, WdlTypeParser}
import wdlTools.util.Options

case class ParseType(opts: Options) extends WdlTypeParser {
  def apply(text: String): AbstractSyntax.Type = {
    val grammarFactory = Draft2TypeGrammarFactory(opts)
    val grammar = grammarFactory.createGrammar(text)
    val visitor = new ParseTypeDocument(grammar, opts)
    val document = visitor.apply()
    grammar.verify()
    document
  }
}
