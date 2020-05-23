package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers, SyntaxError}
import wdlTools.types.{TypeError, TypeInfer, TypeOptions, Unification}
import wdlTools.util.{Options, TypeCheckingRegime}

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

case class Linter(opts: Options,
                  rules: Map[String, RuleConf],
                  events: mutable.Map[URL, mutable.Buffer[LintEvent]] = mutable.HashMap.empty) {
  def hasEvents: Boolean = events.nonEmpty

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

  def hasEvents(url: URL): Boolean = {
    events.get(url).exists(_.nonEmpty)
  }

  def apply(url: URL): Unit = {
    val parsers = Parsers(
        opts,
        listenerFactories = Vector(LinterParserRuleFactory(rules, events)),
        errorHandler = Some(handleParserErrors)
    )
    val astRules = rules.view.filterKeys(AbstractSyntaxTreeRules.allRules.contains)
    val tstRules = rules.view.filterKeys(TypedAbstractSyntaxTreeRules.allRules.contains)
    val typeOpts = TypeOptions(
        localDirectories = opts.localDirectories,
        verbosity = opts.verbosity,
        antlr4Trace = opts.antlr4Trace,
        typeChecking = TypeCheckingRegime.Strict
    )
    val unification = Unification(typeOpts)
    parsers.getDocumentWalker[mutable.Buffer[LintEvent]](url, events).walk { (doc, _) =>
      // stop if the document already has lint events due to parser errors
      val tDoc = if (!hasEvents(url) && tstRules.nonEmpty) {
        // First run the TypeChecker to infer the types of all expressions
        // and catch any type exceptions
        val typeChecker = TypeInfer(typeOpts, Some(handleTypeErrors))
        val (tDoc, _) = typeChecker.apply(doc)
        Some(tDoc)
      } else {
        None
      }
      // stop if the document already has lint events due to type errors
      if (!hasEvents(url)) {
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
