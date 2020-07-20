package wdlTools.linter.v1

import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.linter.{LintEvent, Linter, Severity}
import wdlTools.syntax.SourceLocation
import wdlTools.util.{FileSource, FileSourceResolver}

class BaseTest extends AnyFlatSpec with Matchers {
  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/linter/${subdir}/${fname}").getPath)
  }

  private def getWdlSource(fname: String, subdir: String): FileSource = {
    FileSourceResolver.get.fromPath(getWdlPath(fname, subdir))
  }

  it should "detect lints" in {
    val linter = Linter()
    val docSource = getWdlSource("simple.wdl", "v1")
    val allLints = linter.apply(docSource)
    val lints = allLints(docSource)

    lints.size shouldBe 8

    lints(0) should matchPattern {
      case LintEvent("P001", Severity.Error, SourceLocation(_, 1, 7, 1, 9), _) =>
    }
    lints(1) should matchPattern {
      case LintEvent("P004", Severity.Error, SourceLocation(_, 1, 26, 3, 3), _) =>
    }
    lints(2) should matchPattern {
      case LintEvent("A001", Severity.Error, SourceLocation(_, 3, 2, 15, 1), _) =>
    }
    lints(3) should matchPattern {
      case LintEvent("A003", Severity.Error, SourceLocation(_, 3, 2, 15, 1), _) =>
    }
    lints(4) should matchPattern {
      case LintEvent("P002", Severity.Error, SourceLocation(_, 6, 3, 9, 12), _) =>
    }
    lints(5) should matchPattern {
      case LintEvent("P003", Severity.Error, SourceLocation(_, 6, 3, 9, 12), _) =>
    }
    lints(6) should matchPattern {
      case LintEvent("P002", Severity.Error, SourceLocation(_, 9, 33, 10, 4), _) =>
    }
    lints(7) should matchPattern {
      case LintEvent("P005", Severity.Error, SourceLocation(_, 11, 3, 11, 7), _) =>
    }
  }
}
