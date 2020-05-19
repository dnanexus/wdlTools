package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers}
import wdlTools.types.{TypeInfer, Unification}
import wdlTools.util.Options

import scala.collection.mutable

case class LinterParserRuleFactory(
    rules: Map[String, RuleConf],
    events: mutable.Map[URL, mutable.Buffer[LintEvent]]
) extends ParseTreeListenerFactory {
  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docEvents: mutable.Buffer[LintEvent] = mutable.ArrayBuffer.empty
    events(grammar.docSourceUrl.get) = docEvents
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

  def handleParserError(docSourceUrl: Option[URL], e: Exception): Boolean = {
    // convert parser exception to LintEvent
    require(docSourceUrl.isDefined)
    val url = docSourceUrl.get
    if (!events.contains(url)) {
      val docEvents = mutable.ArrayBuffer.empty[LintEvent]
      events(url) = docEvents
    }
    events(url)
    true
  }

  def apply(url: URL): Unit = {
    val parsers = Parsers(opts,
                          listenerFactories = Vector(LinterParserRuleFactory(rules, events)),
                          errorHandler = Some(handleParserError))
    // TODO: Catch SyntaxExceptions and TypeExceptions and convert them to LintEvents
    // TODO: Ignore events generated from TypeExceptions that dupilicate events added by AST Rules
    parsers.getDocumentWalker[mutable.Buffer[LintEvent]](url, events).walk { (doc, _) =>
      val wdlVersion = doc.version.value
      val astRules = rules.view.filterKeys(AbstractSyntaxTreeRules.allRules.contains)
      val tstRules = rules.view.filterKeys(TypedAbstractSyntaxTreeRules.allRules.contains)
      if (astRules.nonEmpty || tstRules.nonEmpty) {
        if (!events.contains(doc.sourceUrl)) {
          val docEvents = mutable.ArrayBuffer.empty[LintEvent]
          events(doc.sourceUrl) = docEvents
        }
        val docEvents = events(url)
        // Now execute the linter rules
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
          // Run TypeInfer to infer the types of all expressions
          val (typedDoc, _) = TypeInfer(opts).apply(doc)
          val unification = Unification(opts)
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
          tstWalker.apply(typedDoc)
        }
      }
    }
  }
}
