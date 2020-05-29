package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError}
import wdlTools.types.{TypeError, TypeInfer, TypeOptions, Unification}

case class LinterParserRuleFactory(rules: Map[String, RuleConf]) extends ParseTreeListenerFactory {
  private var listeners: Map[URL, Vector[ParserRules.LinterParserRule]] = Map.empty

  def getEvents(url: URL): Vector[LintEvent] =
    listeners.get(url).map(l => l.flatMap(_.getEvents)).getOrElse(Vector.empty)

  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docListeners = rules.collect {
      case (id, conf) if ParserRules.allRules.contains(id) =>
        ParserRules.allRules(id)(conf, grammar)
    }.toVector
    listeners += (grammar.docSourceUrl.get -> docListeners)
    docListeners
  }
}

object Linter {
  val parserErrorRule: RuleConf =
    RuleConf("P000", "parser_error", "Error while parsing WDL document", Severity.Error, Map.empty)
  val typeErrorRule: RuleConf =
    RuleConf("T000",
             "type_error",
             "Error while performing type inference on WDL document",
             Severity.Error,
             Map.empty)
}

case class Linter(opts: TypeOptions, rules: Map[String, RuleConf]) {
  def apply(url: URL): Map[URL, Vector[LintEvent]] = {
    var parserErrorEvents: Map[URL, Vector[LintEvent]] = Map.empty
    var typeErrorEvents: Map[URL, Vector[LintEvent]] = Map.empty

    def handleParserErrors(errors: Vector[SyntaxError]): Boolean = {
      // convert parser exception to LintEvent
      errors.groupBy(_.docSourceUrl.get).foreach {
        case (url, docErrors) =>
          val docEvents = docErrors.map(err =>
            LintEvent(Linter.parserErrorRule, err.textSource, err.docSourceUrl, Some(err.reason))
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
              LintEvent(Linter.typeErrorRule, err.textSource, err.docSourceUrl, Some(err.reason))
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
    val astRules = rules.view.filterKeys(AbstractSyntaxTreeRules.allRules.contains)
    val tstRules = rules.view.filterKeys(TypedAbstractSyntaxTreeRules.allRules.contains)
    val unification = Unification(opts)
    val result = parsers
      .getDocumentWalker[Map[URL, Vector[LintEvent]]](url, Map.empty)
      .walk { (doc, result) =>
        val docUrl = doc.sourceUrl.get
        // stop if there are lint events due to parser errors
        result + (docUrl -> (result.getOrElse(url, Vector.empty) ++ (
            if (!parserErrorEvents.contains(docUrl) && (astRules.nonEmpty || tstRules.nonEmpty)) {
              val wdlVersion = doc.version.value
              val astEvents = if (astRules.nonEmpty) {
                val astVisitors = astRules.map {
                  case (id, conf) =>
                    AbstractSyntaxTreeRules.allRules(id)(
                        conf,
                        wdlVersion,
                        Some(url)
                    )
                }.toVector
                val astWalker =
                  LinterAbstractSyntaxTreeWalker(opts, astVisitors)
                astWalker.apply(doc)
                astVisitors.flatMap(_.getEvents)
              } else {
                Vector.empty
              }
              // run TypeInfer to infer the types of all expressions and catch any type exceptions
              val typeChecker =
                TypeInfer(opts, Some(handleTypeErrors))
              val (tDoc, _) = typeChecker.apply(doc)
              // stop if the document has lint events due to type errors
              val tstEvents =
                if (!typeErrorEvents
                      .contains(docUrl) && tstRules.nonEmpty) {
                  val tstVisitors = tstRules.map {
                    case (id, conf) =>
                      TypedAbstractSyntaxTreeRules.allRules(id)(
                          conf,
                          wdlVersion,
                          unification,
                          Some(url)
                      )
                  }.toVector
                  val tstWalker =
                    LinterTypedAbstractSyntaxTreeWalker(opts, tstVisitors)
                  tstWalker.apply(tDoc)
                  tstVisitors.flatMap(_.getEvents)
                } else {
                  Vector.empty
                }
              astEvents ++ tstEvents
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
