package wdlTools.linter.v1

import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.linter.{LintEvent, Linter, Severity}
import wdlTools.syntax.TextSource
import wdlTools.types.TypeOptions
import wdlTools.util.FileSource

class BaseTest extends AnyFlatSpec with Matchers {
  private val opts = TypeOptions()

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/linter/${subdir}/${fname}").getPath)
  }

  private def getWdlSource(fname: String, subdir: String): FileSource = {
    opts.fileResolver.fromPath(getWdlPath(fname, subdir))
  }

  it should "detect lints" in {
    val linter = Linter(opts)
    val docSource = getWdlSource("simple.wdl", "v1")
    val allLints = linter.apply(docSource)
    val lints = allLints(docSource)

    lints.size shouldBe 8

    lints(0) should matchPattern {
      case LintEvent("P001", Severity.Error, TextSource(1, 7, 1, 9), docSource, _) =>
    }
    lints(1) should matchPattern {
      case LintEvent("P004", Severity.Error, TextSource(1, 26, 3, 3), docSource, _) =>
    }
    lints(2) should matchPattern {
      case LintEvent("A001", Severity.Error, TextSource(3, 2, 15, 1), docSource, _) =>
    }
    lints(3) should matchPattern {
      case LintEvent("A003", Severity.Error, TextSource(3, 2, 15, 1), docSource, _) =>
    }
    lints(4) should matchPattern {
      case LintEvent("P002", Severity.Error, TextSource(6, 3, 9, 12), docSource, _) =>
    }
    lints(5) should matchPattern {
      case LintEvent("P003", Severity.Error, TextSource(6, 3, 9, 12), docSource, _) =>
    }
    lints(6) should matchPattern {
      case LintEvent("P002", Severity.Error, TextSource(9, 33, 10, 4), docSource, _) =>
    }
    lints(7) should matchPattern {
      case LintEvent("P005", Severity.Error, TextSource(11, 3, 11, 7), docSource, _) =>
    }
  }
}
