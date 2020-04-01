package wdlTools.format

import java.nio.file.{Path, Paths}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.syntax.ParseDocument
import wdlTools.util.Options
import wdlTools.util.URL

class BaseTest extends FlatSpec with Matchers {

  private def getWdlSource(fname: String): URL = {
    val p: String = getClass.getResource(s"/format/${fname}").getPath
    val path: Path = Paths.get(p)
    URL(path.toString)
  }
  private lazy val conf = Options(antlr4Trace = false)

  it should "handle the runtime section correctly" in {
    val doc = ParseDocument.apply(getWdlSource("simple.wdl"), conf)
    doc.version shouldBe ("1.0")
  }
}
