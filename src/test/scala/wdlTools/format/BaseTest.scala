package wdlTools.format

import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.generators.code
import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.util.{BasicOptions, FileSource, LinesFileSource, Util}

class BaseTest extends AnyFlatSpec with Matchers {
  private val opts = BasicOptions()
  private val parsers = Parsers(opts)
  private val v1Parser = parsers.getParser(WdlVersion.V1)

  def getWdlPath(fname: String, subdir: String): Path = {
    Util.absolutePath(Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath))
  }

  def getWdlSource(fname: String, subdir: String): FileSource = {
    opts.fileResolver.fromPath(getWdlPath(fname, subdir))
  }

  it should "handle the runtime section correctly" in {
    val doc = v1Parser.parseDocument(getWdlSource(fname = "simple.wdl", subdir = "after"))
    doc.version.value shouldBe WdlVersion.V1
  }

  it should "reformat simple WDL" in {
    val beforeSrc = getWdlSource(fname = "simple.wdl", subdir = "before")
    val expected = getWdlSource(fname = "simple.wdl", subdir = "after")
    val formatter = code.WdlV1Formatter(opts)
    val documents = formatter.formatDocuments(beforeSrc)
    documents(beforeSrc).mkString("\n") shouldBe expected.readString
  }

  it should "format add.wdl" in {
    val beforeSrc = getWdlSource(fname = "add.wdl", subdir = "before")
    val expected = getWdlSource(fname = "add.wdl", subdir = "after")
    val formatter = code.WdlV1Formatter(opts)
    val documents = formatter.formatDocuments(beforeSrc)
    documents(beforeSrc).mkString("\n") shouldBe expected.readString
  }

  it should "format task with complex metadata" in {
    val beforeSrc = getWdlSource(fname = "meta_object_values.wdl", subdir = "before")
    val doc = v1Parser.parseDocument(beforeSrc)
    val formatter = code.WdlV1Formatter(opts)
    val lines = formatter.formatDocument(doc)
    // test that it parses successfully
    v1Parser.parseDocument(LinesFileSource(lines))
  }
}
