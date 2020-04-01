package wdlTools.format

import java.nio.file.{Path, Paths}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.syntax.ParseDocument
import wdlTools.util.Options
import wdlTools.util.URL

class BaseTest extends FlatSpec with Matchers {
  private lazy val conf = Options(antlr4Trace = false)

  private def getWdlSource(fname: String): URL = {
    val p: String = getClass.getResource(s"/format/${fname}").getPath
    val path: Path = Paths.get(p)
    URL(path.toString)
  }

  it should "handle the runtime section correctly" in {
    val parser = ParseAll(conf)
    val src = getWdlSource(fname = "simple.wdl", subdir = "after")
    val doc = parser.apply(src)
    doc.version shouldBe "1.0"
  }

  it should "reformat simple WDL" in {
    val beforeURI = getWdlPath(fname = "simple.wdl", subdir = "before").toUri
    val expected = getWdlSource(fname = "simple.wdl", subdir = "after")
    val formatter = WDL10Formatter(conf)
    formatter.formatDocuments(beforeURI)
    formatter.documents(beforeURI).mkString("\n") shouldBe expected
  }
}
