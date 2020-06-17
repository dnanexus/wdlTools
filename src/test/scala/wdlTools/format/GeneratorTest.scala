package wdlTools.format

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.eval.{Context, Eval, EvalConfig}
import wdlTools.generators.code
import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.types.{TypeInfer, TypeOptions, TypedAbstractSyntax => TAT}
import wdlTools.util.{SourceCode, Util}

class GeneratorTest extends AnyFlatSpec with Matchers {
  private val opts = TypeOptions()
  private val parsers = Parsers(opts)
  private val v1Parser = parsers.getParser(WdlVersion.V1)
  private val typeInfer = TypeInfer(opts)

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlUrl(fname: String, subdir: String): URL = {
    Util.pathToUrl(getWdlPath(fname, subdir))
  }

  private def evalCommand(tDoc: TAT.Document, url: Option[URL] = None): Vector[String] = {
    val evaluator = Eval(opts, EvalConfig.empty, wdlTools.syntax.WdlVersion.V1, url)
    tDoc.elements should not be empty
    tDoc.elements.collect {
      case task: TAT.Task =>
        val ctx = evaluator.applyDeclarations(task.declarations, Context(Map.empty))
        evaluator.applyCommand(task.command, ctx)
    }
  }

  private def generate(
      fname: String,
      validate: Boolean = true
  ): (TAT.Document, Vector[String], Option[TAT.Document]) = {
    val beforeUrl = getWdlUrl(fname = fname, subdir = "before")
    val doc = v1Parser.parseDocument(beforeUrl)
    val (tDoc, _) = typeInfer.apply(doc)
    val generator = code.WdlV1Generator()
    val gLines = generator.generateDocument(tDoc)
    val gtDoc = if (validate) {
      val gDoc = v1Parser.parseDocument(SourceCode(None, gLines))
      Some(typeInfer.apply(gDoc)._1)
    } else {
      None
    }
    (tDoc, gLines, gtDoc)
  }

  it should "handle deep nesting" in {
    generate("deep_nesting.wdl")
  }

  it should "handle object values in meta" in {
    generate("meta_object_values.wdl")
  }

  it should "handle workflow with calls" in {
    generate("wf_with_call.wdl")
  }

  it should "handle empty calls" in {
    generate("empty_call.wdl")
  }

  it should "handle optionals" in {
    generate("optionals.wdl")
  }

  it should "handle command block" in {
    val (tDoc, _, gtDoc) = generate("python_heredoc.wdl")
    val expected1 =
      """python <<CODE
        |import os
        |import sys
        |print("We are inside a python docker image")
        |CODE""".stripMargin
    val expected2 =
      """python <<CODE
        |import os
        |dir_path_A = os.path.dirname("/home/dnanexus/inputs/reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
        |dir_path_B = os.path.dirname("/home/dnanexus/inputs/1/reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
        |print((dir_path_A == dir_path_B))
        |CODE""".stripMargin
    val expected = Vector(expected1, expected2)
    evalCommand(tDoc) shouldBe expected
    evalCommand(gtDoc.get) shouldBe expected
  }
}
