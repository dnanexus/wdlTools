package wdlTools.linter.v1

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.linter.{LintEvent, Linter, ParserRules, RuleConf, Severity}
import wdlTools.syntax.TextSource
import wdlTools.util.{Options, Util}

class BaseTest extends AnyFlatSpec with Matchers {
  private val opts = Options()

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/wdlTools/lint/${subdir}/${fname}").getPath)
  }

  private def getWdlUrl(fname: String, subdir: String): URL = {
    Util.pathToUrl(getWdlPath(fname, subdir))
  }

  it should "detect lints" in {
    val rules: Map[String, RuleConf] =
      (ParserRules.allRules.keys.toSet ++ Set("A001", "A002", "A003"))
        .map(id => id -> RuleConf(id, "x", "x", Severity.Error, Map.empty))
        .toMap
    val linter = Linter(opts, rules)
    val url = getWdlUrl("simple.wdl", "v1")
    linter.apply(url)

    val lints = linter.getOrderedEvents(url)
    lints.size shouldBe 8

    inside(lints(0)) {
      case LintEvent(RuleConf("P001", _, _, _, _), TextSource(1, 7, 1, 9), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(1)) {
      case LintEvent(RuleConf("P004", _, _, _, _), TextSource(1, 26, 3, 3), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(2)) {
      case LintEvent(RuleConf("A001", _, _, _, _), TextSource(3, 2, 15, 1), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(3)) {
      case LintEvent(RuleConf("A003", _, _, _, _), TextSource(3, 2, 15, 1), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(4)) {
      case LintEvent(RuleConf("P002", _, _, _, _), TextSource(6, 3, 9, 12), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(5)) {
      case LintEvent(RuleConf("P003", _, _, _, _), TextSource(6, 3, 9, 12), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(6)) {
      case LintEvent(RuleConf("P002", _, _, _, _), TextSource(9, 33, 10, 4), u, _) =>
        u shouldEqual Some(url)
    }
    inside(lints(7)) {
      case LintEvent(RuleConf("P005", _, _, _, _), TextSource(11, 3, 11, 7), u, _) =>
        u shouldEqual Some(url)
    }
  }
}
