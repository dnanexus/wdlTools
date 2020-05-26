package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError}
import wdlTools.types.{TypeError, TypeInfer, TypeOptions, Unification}

import scala.collection.mutable

case class LinterParserRuleFactory(
    rules: Map[String, RuleConf],
    events: mutable.Map[URL, mutable.Buffer[LintEvent]]
) extends ParseTreeListenerFactory {
  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docEvents =
      events.getOrElseUpdate(grammar.docSourceUrl.get, mutable.ArrayBuffer.empty[LintEvent])
    rules.collect {
      case (id, conf) if ParserRules.allRules.contains(id) =>
        ParserRules.allRules(id)(conf, docEvents, grammar)
    }.toVector
  }
}

case class Linter(opts: TypeOptions,
                  rules: Map[String, RuleConf],
                  events: mutable.Map[URL, mutable.Buffer[LintEvent]] = mutable.HashMap.empty) {
  def hasAnyEvents: Boolean = events.nonEmpty

  def hasEvents(url: URL, ruleIds: Set[String] = Set.empty): Boolean = {
    val docEvents = events.get(url)
    if (docEvents.nonEmpty && ruleIds.nonEmpty) {
      docEvents.exists(_.map(event => ruleIds.contains(event.rule.id)).toVector.exists(b => b))
    } else {
      docEvents.exists(_.nonEmpty)
    }
  }

  def getOrderedEvents: Map[URL, Vector[LintEvent]] =
    events.map {
      case (url, docEvents) => url -> docEvents.toVector.sortWith(_ < _)
    }.toMap

  private val parserErrorRule =
    RuleConf("P000", "parser_error", "Error while parsing WDL document", Severity.Error, Map.empty)

  private def handleParserErrors(errors: Vector[SyntaxError]): Boolean = {
    // convert parser exception to LintEvent
    errors.foreach { err =>
      events
        .getOrElseUpdate(err.docSourceUrl.get, mutable.ArrayBuffer.empty[LintEvent])
        .append(LintEvent(parserErrorRule, err.textSource, err.docSourceUrl, Some(err.reason)))
    }
    false
  }

  private val typeErrorRule =
    RuleConf("T000",
             "type_error",
             "Error while performing type inference on WDL document",
             Severity.Error,
             Map.empty)

  private def handleTypeErrors(errors: Vector[TypeError]): Boolean = {
    errors.foreach { err =>
      events
        .getOrElseUpdate(err.docSourceUrl.get, mutable.ArrayBuffer.empty[LintEvent])
        .append(LintEvent(typeErrorRule, err.textSource, err.docSourceUrl, Some(err.reason)))
    }
    false
  }

  def apply(url: URL): Unit = {
    val parsers = Parsers(
        opts,
        listenerFactories = Vector(LinterParserRuleFactory(rules, events)),
        errorHandler = Some(handleParserErrors)
    )
    val astRules = rules.view.filterKeys(AbstractSyntaxTreeRules.allRules.contains)
    val tstRules = rules.view.filterKeys(TypedAbstractSyntaxTreeRules.allRules.contains)
    val unification = Unification(opts)
    parsers.getDocumentWalker[mutable.Buffer[LintEvent]](url, events).walk { (doc, _) =>
      // stop if the document already has lint events due to parser errors
      if (!hasEvents(url, Set(parserErrorRule.id))) {
        val tDoc = if (tstRules.nonEmpty) {
          // First run the TypeChecker to infer the types of all expressions
          // and catch any type exceptions
          val typeChecker = TypeInfer(opts, Some(handleTypeErrors))
          val (tDoc, _) = typeChecker.apply(doc)
          Some(tDoc)
        } else {
          None
        }
        // stop if the document already has lint events due to type errors
        if (!hasEvents(url, Set(typeErrorRule.id))) {
          val wdlVersion = doc.version.value
          val docEvents = events.getOrElseUpdate(url, mutable.ArrayBuffer.empty[LintEvent])
          if (astRules.nonEmpty) {
            val astVisitors = astRules.map {
              case (id, conf) =>
                AbstractSyntaxTreeRules.allRules(id)(
                    conf,
                    wdlVersion,
                    docEvents,
                    Some(url)
                )
            }.toVector
            val astWalker = LinterAbstractSyntaxTreeWalker(opts, astVisitors)
            astWalker.apply(doc)
          }
          if (tstRules.nonEmpty) {
            val tstVisitors = tstRules.map {
              case (id, conf) =>
                TypedAbstractSyntaxTreeRules.allRules(id)(
                    conf,
                    wdlVersion,
                    unification,
                    docEvents,
                    Some(url)
                )
            }.toVector
            val tstWalker = LinterTypedAbstractSyntaxTreeWalker(opts, tstVisitors)
            tstWalker.apply(tDoc.get)
          }
        }
      }
    }
  }
}
