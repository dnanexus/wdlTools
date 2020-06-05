package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError}
import wdlTools.types.{TypeError, TypeInfer, TypeOptions}

case class LinterParserRuleFactory(rules: Map[String, Severity]) extends ParseTreeListenerFactory {
  private var listeners: Map[URL, Vector[Rules.LinterParserRule]] = Map.empty

  def getEvents(url: URL): Vector[LintEvent] =
    listeners.get(url).map(l => l.flatMap(_.getEvents)).getOrElse(Vector.empty)

  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docListeners = rules.collect {
      case (id, severity) if Rules.parserRules.contains(id) =>
        Rules.parserRules(id)(id, severity, grammar)
    }.toVector
    listeners += (grammar.docSourceUrl.get -> docListeners)
    docListeners
  }
}

case class Linter(opts: TypeOptions, rules: Map[String, Severity] = Rules.defaultRules) {
  def apply(url: URL): Map[URL, Vector[LintEvent]] = {
    var parserErrorEvents: Map[URL, Vector[LintEvent]] = Map.empty
    var typeErrorEvents: Map[URL, Vector[LintEvent]] = Map.empty

    def handleParserErrors(errors: Vector[SyntaxError]): Boolean = {
      // convert parser exception to LintEvent
      errors.groupBy(_.docSourceUrl.get).foreach {
        case (url, docErrors) =>
          val docEvents = docErrors.map(err =>
            LintEvent("P000", Severity.Error, err.textSource, err.docSourceUrl, Some(err.reason))
          )
          parserErrorEvents += (url -> (parserErrorEvents
            .getOrElse(url, Vector.empty) ++ docEvents))
      }
      false
    }

    def handleTypeErrors(errors: Vector[TypeError]): Boolean = {
      errors.groupBy(_.docSourceUrl.get).foreach {
        case (url, docErrors) =>
          val docEvents = docErrors
            .map(err =>
              LintEvent("T000", Severity.Error, err.textSource, err.docSourceUrl, Some(err.reason))
            )
          typeErrorEvents += (url -> (typeErrorEvents.getOrElse(url, Vector.empty) ++ docEvents))
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
    val result = parsers.getDocumentWalker[Map[URL, Vector[LintEvent]]](url, Map.empty).walk {
      (doc, result) =>
        val docUrl = doc.sourceUrl.get
        result + (docUrl -> (result.getOrElse(url, Vector.empty) ++ (
            if (!parserErrorEvents.contains(docUrl) && astRules.nonEmpty) {
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
                      doc.sourceUrl
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
      case (url, treeEvents) =>
        val parserErrors = parserErrorEvents.getOrElse(url, Vector.empty)
        val typeErrors = typeErrorEvents.getOrElse(url, Vector.empty)
        val parserEvents = parserRulesFactory.getEvents(url)
        url -> (parserErrors ++ typeErrors ++ parserEvents ++ treeEvents).sortWith(_ < _)
    }
  }
}
