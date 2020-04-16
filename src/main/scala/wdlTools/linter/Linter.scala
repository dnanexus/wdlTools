package wdlTools.linter

import java.net.URL

import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.Options

import scala.collection.mutable

case class Linter(opts: Options) {
  def apply(url: URL): Unit = {
    val errors: mutable.Buffer[LinterError] = mutable.ArrayBuffer.empty
    val listenerFactories = Vector(
        LinterParserRuleFactory[Rules.WhitespaceTabsRule](errors)
    )
    val parsers = Parsers(opts, listenerFactories = listenerFactories)
    val parser: ParseAll = parsers.getParser(WdlVersion.V1).asInstanceOf[ParseAll]
    val doc = parser.apply(url)

    val visitors = Vector()
    val astWalker = LinterASTWalker(opts, visitors)
    astWalker.apply(doc)

    errors.foreach { err =>
      println(s"${err.textSource} ${err.ruleId}")
    }
  }
}
