package wdlTools.linter

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError}
import wdlTools.types.{TypeError, TypeInfer, TypeOptions}
import wdlTools.util.FileSource

case class LinterParserRuleFactory(rules: Map[String, Severity]) extends ParseTreeListenerFactory {
  private var listeners: Map[FileSource, Vector[Rules.LinterParserRule]] = Map.empty

  def getEvents(docSource: FileSource): Vector[LintEvent] =
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

case class Linter(opts: TypeOptions, rules: Map[String, Severity] = Rules.defaultRules) {
  def apply(docSource: FileSource): Map[FileSource, Vector[LintEvent]] = {
    var parserErrorEvents: Map[FileSource, Vector[LintEvent]] = Map.empty
    var typeErrorEvents: Map[FileSource, Vector[LintEvent]] = Map.empty

    def handleParserErrors(errors: Vector[SyntaxError]): Boolean = {
      // convert parser exception to LintEvent
      errors.groupBy(_.fileSource).foreach {
        case (fileSource, docErrors) =>
          val docEvents = docErrors.map(err =>
            LintEvent("P000", Severity.Error, err.textSource, err.fileSource, Some(err.reason))
          )
          parserErrorEvents += (fileSource -> (parserErrorEvents
            .getOrElse(fileSource, Vector.empty) ++ docEvents))
      }
      false
    }

    def handleTypeErrors(errors: Vector[TypeError]): Boolean = {
      errors.groupBy(_.docSource).foreach {
        case (uri, docErrors) =>
          val docEvents = docErrors
            .map(err =>
              LintEvent("T000", Severity.Error, err.textSource, err.docSource, Some(err.reason))
            )
          typeErrorEvents += (uri -> (typeErrorEvents.getOrElse(uri, Vector.empty) ++ docEvents))
      }
      false
    }

    val parserRulesFactory = LinterParserRuleFactory(rules)
    val parsers = Parsers(
        opts,
        listenerFactories = Vector(parserRulesFactory),
        errorHandler = Some(handleParserErrors)
    )
    val astRules = rules.view.filterKeys(Rules.astRules.contains)
    val result =
      parsers.getDocumentWalker[Map[FileSource, Vector[LintEvent]]](docSource, Map.empty).walk {
        (doc, result) =>
          result + (doc.source -> (result.getOrElse(doc.source, Vector.empty) ++ (
              if (!parserErrorEvents.contains(doc.source) && astRules.nonEmpty) {
                // First run the TypeChecker to infer the types of all expressions
                val typeChecker = TypeInfer(opts, errorHandler = Some(handleTypeErrors))
                val (_, typesContext) = typeChecker.apply(doc)
                // Now execute the linter rules
                val astVisitors = astRules.map {
                  case (id, severity) =>
                    Rules.astRules(id)(
                        id,
                        severity,
                        doc.version.value,
                        typesContext,
                        doc.source
                    )
                }.toVector
                val astWalker = LinterASTWalker(opts, astVisitors)
                astWalker.apply(doc)
                astVisitors.flatMap(_.getEvents)
              } else {
                Vector.empty
              }
          )))
      }

    result.map {
      case (uri, treeEvents) =>
        val parserErrors = parserErrorEvents.getOrElse(uri, Vector.empty)
        val typeErrors = typeErrorEvents.getOrElse(uri, Vector.empty)
        val parserEvents = parserRulesFactory.getEvents(uri)
        uri -> (parserErrors ++ typeErrors ++ parserEvents ++ treeEvents).sortWith(_ < _)
    }
  }
}
