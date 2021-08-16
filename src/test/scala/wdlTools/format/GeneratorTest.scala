package wdlTools.format

import java.nio.file.{Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.eval.{DefaultEvalPaths, Eval, WdlValueBindings}
import wdlTools.generators.code.WdlGenerator
import wdlTools.syntax.{Parsers, SyntaxException, WdlVersion}
import wdlTools.types.{TypeInfer, TypedAbstractSyntax => TAT}
import dx.util.{FileNode, FileSourceResolver, LinesFileNode}

class GeneratorTest extends AnyFlatSpec with Matchers {
  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlSource(fname: String, subdir: String): FileNode = {
    FileSourceResolver.get.fromPath(getWdlPath(fname, subdir))
  }

  private def evalCommand(tDoc: TAT.Document): Vector[String] = {
    val evaluator = Eval(DefaultEvalPaths.empty, Some(wdlTools.syntax.WdlVersion.V1))
    tDoc.elements.size should not be 0
    tDoc.elements.collect {
      case task: TAT.Task =>
        val ctx = evaluator.applyPrivateVariables(task.privateVariables, WdlValueBindings.empty)
        evaluator.applyCommand(task.command, ctx)
    }
  }

  /**
    * Parse a WDL file and generate a new WDL from the AST.
    * @param fname the filename to parse
    * @param validateParse if true, the generated WDL is parsed to make sure it's valid
    * @param validateContentSelf if true, checks that the generated WDL is identical to the source WDL
    * @param validateContentFile validates the generated WDL against the specified file
    * @param wdlVersion the version of WDL to generate
    * @return
    */
  private def generate(
      fname: String,
      validateParse: Boolean = true,
      validateContentSelf: Boolean = false,
      validateContentFile: Boolean = false,
      wdlVersion: WdlVersion = WdlVersion.V1
  ): (FileNode, TAT.Document, FileNode, Option[TAT.Document]) = {
    val beforeSrc = getWdlSource(fname = fname, subdir = "before")
    val doc = Parsers.default.parseDocument(beforeSrc)
    val (tDoc, _) = TypeInfer.instance.apply(doc)
    val generator = WdlGenerator(Some(wdlVersion))
    val gLines = LinesFileNode(generator.generateDocument(tDoc))
    //println(gLines.readLines.mkString("\n"))
    if (validateContentSelf) {
      gLines.readLines.mkString("\n") shouldBe beforeSrc.readLines.mkString("\n")
    } else if (validateContentFile) {
      val afterSrc = getWdlSource(fname = fname, subdir = "after")
      gLines.readLines.mkString("\n") shouldBe afterSrc.readLines.mkString("\n")
    }
    val gtDoc = if (validateParse) {
      val gDoc =
        try {
          Parsers.default.parseDocument(gLines)
        } catch {
          case ex: SyntaxException =>
            println(s"Generated WDL has invalid syntax\n${gLines.readLines.mkString("\n")}")
            throw ex
        }
      Some(TypeInfer.instance.apply(gDoc)._1)
    } else {
      None
    }
    (beforeSrc, tDoc, gLines, gtDoc)
  }

  it should "regenerate a simple WDL" in {
    generate("simple.wdl")
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
        |dir_path_A = os.path.dirname("reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
        |dir_path_B = os.path.dirname("1/reallyreallyreallyreallyreallyreallyreallyreallyreallyreallyreallylongfilename")
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

  it should "correctly format object literal" in {
    generate("struct_literal.wdl", validateContentFile = true)
  }

  it should "handle calls inputs with function call values" in {
    generate("call_with_function.wdl", validateContentFile = true)
  }

  it should "regenerate a WDL with only a placeholder in the command" in {
    generate("bug-382.wdl")
  }

  it should "escape strings" in {
    generate("escape_sequences.wdl", validateContentSelf = true)
  }

  it should "include quotes for strings in placeholders" in {
    generate("sum_array.wdl", validateContentSelf = true)
  }

  it should "handle strings with nested quotes" in {
    generate("nested_quotes.wdl", validateContentFile = true)
  }

  it should "handle struct field access" in {
    generate("struct_field.wdl", validateContentSelf = true)
  }

  it should "handle None literal" in {
    generate("none_literal.wdl", validateContentSelf = true, wdlVersion = WdlVersion.V2)
  }
}
