package wdlTools.format

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.generators.code
import wdlTools.syntax.v1
import wdlTools.types.{TypeInfer, TypeOptions}
import wdlTools.util.{SourceCode, Util}

class GeneratorTest extends AnyFlatSpec with Matchers {
  private val opts = TypeOptions()
  private val parser = v1.ParseAll(opts)
  private val typeInfer = TypeInfer(opts)

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlUrl(fname: String, subdir: String): URL = {
    Util.pathToUrl(getWdlPath(fname, subdir))
  }

  it should "handle deep nesting" in {
    val beforeURL = getWdlUrl(fname = "deep_nesting.wdl", subdir = "before")
    val doc = parser.parseDocument(beforeURL)
    val (tDoc, _) = typeInfer.apply(doc)
    val generator = code.WdlV1Generator()
    generator.generateDocument(tDoc)
  }

  it should "handle object values in meta" in {
    val beforeURL = getWdlUrl(fname = "meta_object_values.wdl", subdir = "before")
    val doc = parser.parseDocument(beforeURL)
    val (tDoc, _) = typeInfer.apply(doc)
    val generator = code.WdlV1Generator()
    val gLines = generator.generateDocument(tDoc)
    // test that it parses successfully
    val gDoc = parser.parseDocument(SourceCode(None, gLines))
    typeInfer.apply(gDoc)
  }

  it should "handle workflow with calls" in {
    val beforeURL = getWdlUrl(fname = "wf_with_calL.wdl", subdir = "before")
    val doc = parser.parseDocument(beforeURL)
    val (tDoc, _) = typeInfer.apply(doc)
    val generator = code.WdlV1Generator()
    val gLines = generator.generateDocument(tDoc)
    // test that it parses successfully
    val gDoc = parser.parseDocument(SourceCode(None, gLines))
    typeInfer.apply(gDoc)
    // TODO: test that tDoc == gtDoc
  }
}
