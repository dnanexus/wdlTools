package wdlTools.linter

import java.net.URL

import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.Options

import scala.collection.mutable

case class Linter(opts: Options) {
  def apply(url: URL): Unit = {
    val parsers = Parsers(opts)
    val parser: ParseAll = parsers.getParser(WdlVersion.V1).asInstanceOf[ParseAll]
    val errors: mutable.Buffer[LinterError] = mutable.ArrayBuffer.empty
    parser.addParserListenerFactory(LinterParserRuleFactory[Rules.WhitespaceTabsRule](errors))
    parser.apply(url)
    errors.foreach { err =>
      println(s"${err.textSource} ${err.ruleId}")
    }
  }
}
