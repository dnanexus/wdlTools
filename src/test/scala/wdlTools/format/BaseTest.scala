package wdlTools.format

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.generators.code
import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.util.{BasicOptions, SourceCode, Util}

class BaseTest extends AnyFlatSpec with Matchers {
  private val opts = BasicOptions()
  private val parsers = Parsers(opts)
  private val v1Parser = parsers.getParser(WdlVersion.V1)

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlUrl(fname: String, subdir: String): URL = {
    Util.pathToUrl(getWdlPath(fname, subdir))
  }

  it should "handle the runtime section correctly" in {
    val doc = v1Parser.parseDocument(getWdlUrl(fname = "simple.wdl", subdir = "after"))
    doc.version.value shouldBe WdlVersion.V1
  }

  def getWdlSource(fname: String, subdir: String): String = {
    Util.readFileContent(getWdlPath(fname, subdir))
  }

  it should "reformat simple WDL" in {
    val beforeURL = getWdlUrl(fname = "simple.wdl", subdir = "before")
    val expected = getWdlSource(fname = "simple.wdl", subdir = "after")
    val formatter = code.WdlV1Formatter(opts)
    val documents = formatter.formatDocuments(beforeURL)
    documents(beforeURL).mkString("\n") shouldBe expected
  }

  it should "format add.wdl" in {
    val beforeURL = getWdlUrl(fname = "add.wdl", subdir = "before")
    val expected = getWdlSource(fname = "add.wdl", subdir = "after")
    val formatter = code.WdlV1Formatter(opts)
    val documents = formatter.formatDocuments(beforeURL)
    documents(beforeURL).mkString("\n") shouldBe expected
  }

  it should "format task with complex metadata" in {
    val beforeURL = getWdlUrl(fname = "meta_object_values.wdl", subdir = "before")
    val doc = v1Parser.parseDocument(beforeURL)
    val formatter = code.WdlV1Formatter(opts)
    val lines = formatter.formatDocument(doc)
    // test that it parses successfully
    v1Parser.parseDocument(SourceCode(None, lines))
  }
}
