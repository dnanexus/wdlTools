package wdlTools.format

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.formatter.WdlV1Formatter
import wdlTools.syntax.{WdlVersion, v1}
import wdlTools.util.{Options, SourceCode, Util}

class BaseTest extends FlatSpec with Matchers {
  private lazy val opts = Options()
  private lazy val loader = SourceCode.Loader(opts)
  private lazy val parser = v1.ParseAll(opts, loader)

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlURL(fname: String, subdir: String): URL = {
    Util.getURL(getWdlPath(fname, subdir))
  }

  it should "handle the runtime section correctly" in {
    val doc = parser.parse(getWdlURL(fname = "simple.wdl", subdir = "after"))
    doc.version.value shouldBe WdlVersion.V1
  }

  def getWdlSource(fname: String, subdir: String): String = {
    Util.readFromFile(getWdlPath(fname, subdir))
  }

  it should "reformat simple WDL" in {
    val beforeURL = getWdlURL(fname = "simple.wdl", subdir = "before")
    val expected = getWdlSource(fname = "simple.wdl", subdir = "after")
    val formatter = WdlV1Formatter(opts)
    formatter.formatDocuments(beforeURL)
    formatter.documents(beforeURL).mkString("\n") shouldBe expected
  }
}
