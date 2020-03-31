package wdlTools.format

import java.net.URI
import java.nio.file.{Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.formatter.WDL10Formatter
import wdlTools.syntax.ParseAll
import wdlTools.util.{Options, Util}

class BaseTest extends FlatSpec with Matchers {
  private lazy val conf = Options(antlr4Trace = false)

  def getWdlSource(fname: String, subdir: String): (URI, String) = {
    val p: String = getClass.getResource(s"/format/${subdir}/${fname}").getPath
    val path: Path = Paths.get(p)
    val uri: URI = path.toUri
    (uri, Util.readFromFile(path))
  }

  def getWdlSourcePair(fname: String): (URI, String, String) = {
    val before = getWdlSource(fname, subdir = "before")
    val after = getWdlSource(fname, subdir = "after")
    (before._1, before._2, after._2)
  }

  it should "handle the runtime section correctly" in {
    val parser = ParseAll(conf)
    val src = getWdlSource(fname = "simple.wdl", subdir = "after")
    val doc = parser.apply(src._2)
    doc.version shouldBe "1.0"
  }

  it should "reformat simple WDL" in {
    val (beforeURI, before, after) = getWdlSourcePair(fname = "simple.wdl")
    val parser = ParseAll(conf)
    val beforeDoc = parser.apply(before)

    val formatter = WDL10Formatter()
    formatter.formatDocument(beforeURI, beforeDoc)
    formatter.documents(beforeURI).mkString("\n") shouldBe after
  }
}
