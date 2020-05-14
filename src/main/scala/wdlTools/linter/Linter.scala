package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import wdlTools.linter.Severity.Severity
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{Antlr4Util, Parsers}
import wdlTools.types.{TypeInfer, Unification}
import wdlTools.util.Options

import scala.collection.mutable

case class LinterParserRuleFactory(
    rules: Map[String, Severity],
    events: mutable.Map[URL, mutable.Buffer[LintEvent]]
) extends ParseTreeListenerFactory {
  override def createParseTreeListeners(
      grammar: Antlr4Util.Grammar
  ): Vector[ParseTreeListener] = {
    val docEvents: mutable.Buffer[LintEvent] = mutable.ArrayBuffer.empty
    events(grammar.docSourceUrl.get) = docEvents
    rules.collect {
      case (id, severity) if ParserRules.allRules.contains(id) =>
        ParserRules.allRules(id)(id, severity, docEvents, grammar)
    }.toVector
  }
}

case class Linter(opts: Options,
                  rules: Map[String, Severity] = Linter.defaultRules,
                  events: mutable.Map[URL, mutable.Buffer[LintEvent]] = mutable.HashMap.empty) {
  def hasEvents: Boolean = events.nonEmpty

  def getOrderedEvents: Map[URL, Vector[LintEvent]] =
    events.map {
      case (url, docEvents) => url -> docEvents.toVector.sortWith(_ < _)
    }.toMap

  def apply(url: URL): Unit = {
    val parsers = Parsers(opts, listenerFactories = Vector(LinterParserRuleFactory(rules, events)))
    // TODO: Catch SyntaxExceptions and TypeExceptions and convert them to LintEvents
    // TODO: Ignore events generated from TypeExceptions that dupilicate events added by AST Rules
    parsers.getDocumentWalker[mutable.Buffer[LintEvent]](url, events).walk { (doc, _) =>
      val astRules = rules.view.filterKeys(AstRules.allRules.contains)
      val tstRules = rules.view.filterKeys(TstRules.allRules.contains)
      if (astRules.nonEmpty || tstRules.nonEmpty) {
        if (!events.contains(doc.sourceUrl)) {
          val docEvents = mutable.ArrayBuffer.empty[LintEvent]
          events(doc.sourceUrl) = docEvents
        }
        // Now execute the linter rules
        if (astRules.nonEmpty) {
          val visitors = astRules.map {
            case (id, severity) =>
              AstRules.allRules(id)(
                  id,
                  severity,
                  doc.version.value,
                  events(url),
                  Some(url)
              )
          }.toVector
          val astWalker = LinterAstWalker(opts, visitors)
          astWalker.apply(doc)
        }
        if (tstRules.nonEmpty) {
          // Run TypeInfer to infer the types of all expressions
          // We call the private applyDoc() method rather than the
          // public apply() method because we need the Context
          val (typedDoc, _) = TypeInfer(opts).apply(doc)
          val unification = Unification(opts)
          val visitors = tstRules.map {
            case (id, severity) =>
              TstRules.allRules(id)(
                  id,
                  severity,
                  doc.version.value,
                  unification,
                  events(url),
                  Some(url)
              )
          }.toVector
          val tstWalker = LinterTstWalker(opts, visitors)
          tstWalker.apply(typedDoc)
        }
      }
    }
  }
}

object Linter {
  val defaultRules: Map[String, Severity] = (
      ParserRules.allRules.keys.toVector ++ AstRules.allRules.keys.toVector ++ TstRules.allRules.keys.toVector
  ).map(_ -> Severity.Default).toMap
}
