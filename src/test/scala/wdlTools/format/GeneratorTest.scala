package wdlTools.format

import java.nio.file.{Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.eval.{Context, Eval, EvalPaths}
import wdlTools.generators.code
import wdlTools.syntax.Parsers
import wdlTools.types.{TypeInfer, TypeOptions, TypedAbstractSyntax => TAT}
import wdlTools.util.{FileSource, LinesFileSource}

class GeneratorTest extends AnyFlatSpec with Matchers {
  private val opts = TypeOptions()
  private val parsers = Parsers(opts)
  private val typeInfer = TypeInfer(opts)

  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlSource(fname: String, subdir: String): FileSource = {
    opts.fileResolver.fromPath(getWdlPath(fname, subdir))
  }

  private def evalCommand(tDoc: TAT.Document): Vector[String] = {
    val evaluator =
      Eval(EvalPaths.empty, opts.fileResolver, wdlTools.syntax.WdlVersion.V1, opts.logger)
    tDoc.elements should not be empty
    tDoc.elements.collect {
      case task: TAT.Task =>
        val ctx = evaluator.applyDeclarations(task.declarations, Context(Map.empty))
        evaluator.applyCommand(task.command, ctx)
    }
  }

  private def generate(
      fname: String,
      validateParse: Boolean = true,
      validateContentSelf: Boolean = false,
      validateContentFile: Boolean = false
  ): (FileSource, TAT.Document, FileSource, Option[TAT.Document]) = {
    val beforeSrc = getWdlSource(fname = fname, subdir = "before")
    val doc = parsers.parseDocument(beforeSrc)
    val (tDoc, _) = typeInfer.apply(doc)
    val generator = code.WdlV1Generator()
    val gLines = LinesFileSource(generator.generateDocument(tDoc))
    if (validateContentSelf) {
      gLines.readLines.mkString("\n") shouldBe beforeSrc.readLines.mkString("\n")
    } else if (validateContentFile) {
      val afterSrc = getWdlSource(fname = fname, subdir = "after")
      gLines.readLines.mkString("\n") shouldBe afterSrc.readLines.mkString("\n")
    }
    val gtDoc = if (validateParse) {
      val gDoc = parsers.parseDocument(gLines)
      Some(typeInfer.apply(gDoc)._1)
    } else {
      None
    }
    (beforeSrc, tDoc, gLines, gtDoc)
  }

  it should "handle deep nesting" in {
    generate("deep_nesting.wdl")
  }

  it should "handle object values in meta" in {
    generate("meta_object_values.wdl", validateContentFile = true)
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
    val (_, tDoc, _, gtDoc) = generate("python_heredoc.wdl")
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

  it should "not wrap strings in command block" in {
    generate("library_syscall.wdl")
  }

  it should "correctly indent multi-line command that is a single ValueString" in {
    generate("single_string_multiline_command.wdl", validateContentSelf = true)
  }

  it should "correctly indent single-line command" in {
    generate("single_line_command.wdl", validateContentFile = true)
  }
}
