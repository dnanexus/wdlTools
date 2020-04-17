package wdlTools.syntax.draft_2

import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.draft_2.Translators._
import wdlTools.syntax.{AbstractSyntax, ParseAllBase, TextSource, WdlVersion}
import wdlTools.util.{Options, SourceCode}

// parse and follow imports
case class ParseAll(opts: Options,
                    loader: SourceCode.Loader,
                    listeners: Vector[ParseTreeListenerFactory] = Vector.empty)
    extends ParseAllBase(opts, loader) {
  private val grammarFactory: WdlDraft2GrammarFactory = WdlDraft2GrammarFactory(opts, listeners)

  override def canParse(sourceCode: SourceCode): Boolean = {
    sourceCode.lines.foreach { line =>
      val trimmed = line.trim
      if (!(trimmed.isEmpty || trimmed.startsWith("#"))) {
        return trimmed.trim.startsWith("import") ||
          trimmed.startsWith("workflow") ||
          trimmed.startsWith("task")
      }
    }
    false
  }

  // start from a document [doc], and recursively dive into all the imported
  // documents. Replace all the raw import statements with fully elaborated ones.
  private def dfs(doc: ConcreteSyntax.Document): AbstractSyntax.Document = {
    // translate all the elements of the document to the abstract syntax
    val elems: Vector[AbstractSyntax.DocumentElement] = doc.elements.map {
      case importDoc: ConcreteSyntax.ImportDoc =>
        val importedDoc = if (opts.followImports) {
          Some(followImport(importDoc.url))
        } else {
          None
        }
        translateImportDoc(importDoc, importedDoc)
      case task: ConcreteSyntax.Task => translateTask(task)
      case other                     => throw new Exception(s"unrecognized document element ${other}")
    }

    val aWf = doc.workflow.map(translateWorkflow)
    val version = AbstractSyntax.Version(WdlVersion.Draft_2, TextSource(-1, -1), None)
    AbstractSyntax.Document(version, None, elems, aWf, doc.text, doc.comment)
  }

  def apply(sourceCode: SourceCode): AbstractSyntax.Document = {
    val grammar = grammarFactory.createGrammar(sourceCode)
    val visitor = ParseTop(opts, grammar, Some(sourceCode.url))
    val top: ConcreteSyntax.Document = visitor.parseDocument
    dfs(top)
  }
}
