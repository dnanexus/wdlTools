package wdlTools.syntax.v1_0

import java.net.URL

import org.antlr.v4.runtime.ParserRuleContext
import wdlTools.syntax.Antlr4Util.Antlr4ParserListener
import wdlTools.syntax.v1_0.ConcreteSyntax.Element
import wdlTools.syntax.{AbstractSyntax, WdlParser}
import wdlTools.syntax.v1_0.Translators._
import wdlTools.util.{Options, SourceCode}

import scala.collection.mutable

// parse and follow imports
case class ParseAll(opts: Options, loader: SourceCode.Loader) extends WdlParser(opts, loader) {
  // cache of documents that have already been fetched and parsed.
  private val docCache: mutable.Map[URL, AbstractSyntax.Document] = mutable.Map.empty
  private val grammarFactory: WdlV1GrammarFactory = WdlV1GrammarFactory(opts)

  override def addListener[T <: Element, C <: ParserRuleContext](
      listener: Antlr4ParserListener[T, C]
  ): Unit = {
    grammarFactory.addListener[T, C](listener)
  }

  private def followImport(url: URL): AbstractSyntax.Document = {
    docCache.get(url) match {
      case None =>
        val grammar = grammarFactory.createGrammar(loader.apply(url).toString)
        val visitor = ParseDocument(grammar, url, opts)
        val cDoc: ConcreteSyntax.Document = visitor.apply()
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
      case ConcreteSyntax.TypeStruct(name, members, text, comment) =>
        AbstractSyntax.TypeStruct(
            name,
            members.map {
              case ConcreteSyntax.StructMember(name, t, memberText, memberComment) =>
                AbstractSyntax.StructMember(name, translateType(t), memberText, memberComment)
            },
            text,
            comment
        )

      case ConcreteSyntax.ImportDoc(name, aliases, url, text, comment) =>
        val importedDoc = followImport(url)
        val aliasesAbst: Vector[AbstractSyntax.ImportAlias] = aliases.map {
          case ConcreteSyntax.ImportAlias(x, y, alText) => AbstractSyntax.ImportAlias(x, y, alText)
        }

        // Replace the original statement with a new one
        AbstractSyntax.ImportDoc(name, aliasesAbst, url, importedDoc, text, comment)

      case ConcreteSyntax.Task(name,
                               input,
                               output,
                               command,
                               declarations,
                               meta,
                               parameterMeta,
                               runtime,
                               text,
                               comment) =>
        AbstractSyntax.Task(
            name,
            input.map(translateInputSection),
            output.map(translateOutputSection),
            translateCommandSection(command),
            declarations.map(translateDeclaration),
            meta.map(translateMetaSection),
            parameterMeta.map(translateParameterMetaSection),
            runtime.map(translateRuntimeSection),
            text,
            comment
        )

      case other => throw new Exception(s"unrecognized document element ${other}")
    }

    val aWf = doc.workflow.map(translateWorkflow)
    AbstractSyntax.Document(doc.version.value,
                            Some(doc.version.text),
                            elems,
                            aWf,
                            doc.text,
                            doc.comment)
  }

  override def canParse(sourceCode: SourceCode): Boolean = {
    sourceCode.lines.foreach { line =>
      if (!(line.trim.isEmpty || line.startsWith("#"))) {
        return line.trim.startsWith("version 1.0")
      }
    }
    false
  }

  def apply(url: URL): AbstractSyntax.Document = {
    apply(loader.apply(url))
  }

  def apply(sourceCode: SourceCode): AbstractSyntax.Document = {
    val grammar = grammarFactory.createGrammar(sourceCode.toString)
    val visitor = ParseDocument(grammar, sourceCode.url, opts)
    val top: ConcreteSyntax.Document = visitor.apply()
    dfs(top)
  }
}
