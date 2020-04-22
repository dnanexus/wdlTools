package wdlTools.syntax.v1

import java.net.URL

import wdlTools.syntax.v1.Translators._
import wdlTools.syntax.{AbstractSyntax, WdlParser}
import wdlTools.util.{Options, SourceCode}

import scala.collection.mutable

// parse and follow imports
case class ParseAll(opts: Options, loader: SourceCode.Loader) extends WdlParser(opts, loader) {
  // cache of documents that have already been fetched and parsed.
  private val docCache: mutable.Map[URL, AbstractSyntax.Document] = mutable.Map.empty
  private val grammarFactory = WdlV1GrammarFactory(opts)

  private def followImport(url: URL): AbstractSyntax.Document = {
    docCache.get(url) match {
      case None =>
        val grammar = grammarFactory.createGrammar(loader.apply(url).toString)
        val visitor = ParseTop(opts, grammar, Some(url))
        val cDoc: ConcreteSyntax.Document = visitor.parseDocument
        val aDoc = dfs(cDoc)
        docCache(url) = aDoc
        aDoc
      case Some(aDoc) =>
        aDoc
    }
  }

  // start from a document [doc], and recursively dive into all the imported
  // documents. Replace all the raw import statements with fully elaborated ones.
  private def dfs(doc: ConcreteSyntax.Document): AbstractSyntax.Document = {

    // translate all the elements of the document to the abstract syntax
    val elems: Vector[AbstractSyntax.DocumentElement] = doc.elements.map {
      case struct: ConcreteSyntax.TypeStruct => translateStruct(struct)
      case importDoc: ConcreteSyntax.ImportDoc =>
        val importedDoc = if (opts.followImports) {
          Some(followImport(getDocSourceURL(importDoc.addr.value)))
        } else {
          None
        }
        translateImportDoc(importDoc, importedDoc)
      case task: ConcreteSyntax.Task => translateTask(task)
      case other                     => throw new Exception(s"unrecognized document element ${other}")
    }

    val aWf = doc.workflow.map(translateWorkflow)
    val version = AbstractSyntax.Version(doc.version.value, doc.version.text)
    AbstractSyntax.Document(doc.docSourceURL,
                            version,
                            Some(doc.version.text),
                            elems,
                            aWf,
                            doc.text,
                            doc.comments)
  }

  override def canParse(sourceCode: SourceCode): Boolean = {
    sourceCode.lines.foreach { line =>
      if (!(line.trim.isEmpty || line.startsWith("#"))) {
        return line.trim.startsWith("version 1.0")
      }
    }
    false
  }

  override def apply(sourceCode: SourceCode): AbstractSyntax.Document = {
    val grammar = grammarFactory.createGrammar(sourceCode.toString)
    val visitor = ParseTop(opts, grammar, Some(sourceCode.url))
    val top: ConcreteSyntax.Document = visitor.parseDocument
    dfs(top)
  }
}
