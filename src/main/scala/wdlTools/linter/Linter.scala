package wdlTools.linter

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError, SyntaxErrorHandler}
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.types.{TypeCheckingRegime, TypeError, TypeErrorHandler, TypeInfer}
import dx.util.{FileNode, FileSourceResolver, Logger}

case class LinterParserRuleFactory(rules: Map[String, Severity]) extends ParseTreeListenerFactory {
  private var listeners: Map[FileNode, Vector[Rules.LinterParserRule]] = Map.empty

  def getEvents(docSource: FileNode): Vector[LintEvent] =
    listeners.get(docSource).map(l => l.flatMap(_.getEvents)).getOrElse(Vector.empty)

  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docListeners = rules.collect {
      case (id, severity) if Rules.parserRules.contains(id) =>
        Rules.parserRules(id)(id, severity, grammar)
    }.toVector
    listeners += (grammar.docSource -> docListeners)
    docListeners
  }
}

class LinterSyntaxErrorHandler extends SyntaxErrorHandler {
  private var events: Map[FileNode, Vector[LintEvent]] = Map.empty

  def handleSyntaxErrors(errors: Vector[SyntaxError]): Boolean = {
    // convert parser exception to LintEvent
    errors.groupBy(_.loc.source).foreach {
      case (fileSource, docErrors) =>
        val docEvents = docErrors
          .map(err => LintEvent("P000", Severity.Error, err.loc, Some(err.reason)))
        events += (fileSource -> (events.getOrElse(fileSource, Vector.empty) ++ docEvents))
    }
    false
  }

  def hasSyntaxErrors: Boolean = events.nonEmpty

  def contains(source: FileNode): Boolean = {
    events.contains(source)
  }

  def getErrors(source: FileNode): Vector[LintEvent] = {
    events.getOrElse(source, Vector.empty)
  }
}

class LinterTypeErrorHandler extends TypeErrorHandler {
  private var events: Map[FileNode, Vector[LintEvent]] = Map.empty

  def handleTypeErrors(errors: Vector[TypeError]): Boolean = {
    errors.groupBy(_.loc.source).foreach {
      case (uri, docErrors) =>
        val docEvents = docErrors
          .map(err => LintEvent("T000", Severity.Error, err.loc, Some(err.reason)))
        events += (uri -> (events.getOrElse(uri, Vector.empty) ++ docEvents))
    }
    false
  }

  def hasTypeErrors: Boolean = events.nonEmpty

  def getErrors(source: FileNode): Vector[LintEvent] = {
    events.getOrElse(source, Vector.empty)
  }
}

case class Linter(rules: Map[String, Severity] = Rules.defaultRules,
                  regime: TypeCheckingRegime = TypeCheckingRegime.Moderate,
                  followImports: Boolean = true,
                  fileResolver: FileSourceResolver = FileSourceResolver.get,
                  logger: Logger = Logger.get) {
  def apply(docSource: FileNode): Map[FileNode, Vector[LintEvent]] = {
    val syntaxErrorHandler = new LinterSyntaxErrorHandler()
    val typeErrorHandler = new LinterTypeErrorHandler()

    val parserRulesFactory = LinterParserRuleFactory(rules)
    val parsers = Parsers(
        followImports,
        fileResolver,
        listenerFactories = Vector(parserRulesFactory),
        errorHandler = Some(syntaxErrorHandler),
        logger
    )
    val astRules = rules.view.filterKeys(Rules.astRules.contains)
    val result =
      parsers.getDocumentWalker[Map[FileNode, Vector[LintEvent]]](docSource, Map.empty).walk {
        (doc, result) =>
          result + (doc.source -> (result.getOrElse(doc.source, Vector.empty) ++ (
              if (!syntaxErrorHandler.contains(doc.source) && astRules.nonEmpty) {
                // First run the TypeChecker to infer the types of all expressions
                val typeChecker = TypeInfer(regime, errorHandler = Some(typeErrorHandler))
                val (_, typesContext) = typeChecker.apply(doc)
                // Now execute the linter rules
                val astVisitors = astRules.map {
                  case (id, severity) =>
                    Rules.astRules(id)(
                        id,
                        severity,
                        doc.version.value,
                        typesContext
                    )
                }.toVector
                val astWalker = LinterAbstractSyntaxTreeWalker(astVisitors, followImports)
                astWalker.apply(doc)
                astVisitors.flatMap(_.getEvents)
              } else {
                Vector.empty
              }
          )))
      }

    result.map {
      case (source, treeEvents) =>
        val parserErrors = syntaxErrorHandler.getErrors(source)
        val typeErrors = typeErrorHandler.getErrors(source)
        val parserEvents = parserRulesFactory.getEvents(source)
        source -> (parserErrors ++ typeErrors ++ parserEvents ++ treeEvents).sortWith(_ < _)
    }
  }
}
