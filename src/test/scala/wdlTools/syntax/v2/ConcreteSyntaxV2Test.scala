package wdlTools.syntax.v2

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{Antlr4Util, Comment, SourceLocation, WdlVersion}
import wdlTools.syntax.v2.ConcreteSyntax._
import dx.util.{FileNode, FileSourceResolver, Logger}

class ConcreteSyntaxV2Test extends AnyFlatSpec with Matchers {
  private val sourcePath = Paths.get(getClass.getResource("/syntax/v2").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(sourcePath))
  private val logger = Logger.Quiet

  private def getSource(fname: String): FileNode = {
    fileResolver.fromPath(sourcePath.resolve(fname))
  }

  private def getDocument(FileSource: FileNode): Document = {
    Antlr4Util.setParserTrace(true)
    ParseTop(WdlV2Grammar.newInstance(FileSource, Vector.empty, logger = logger)).parseDocument
  }

  it should "handle various types" in {
    val doc = getDocument(getSource("types.wdl"))

    doc.elements.size shouldBe 1
    val elem = doc.elements(0)
    elem shouldBe a[Task]
    val task = elem.asInstanceOf[Task]

    val InputSection(decls, _) = task.input.get
    decls(0) should matchPattern { case Declaration("i", TypeInt(_), None, _)       => }
    decls(1) should matchPattern { case Declaration("s", TypeString(_), None, _)    => }
    decls(2) should matchPattern { case Declaration("x", TypeFloat(_), None, _)     => }
    decls(3) should matchPattern { case Declaration("b", TypeBoolean(_), None, _)   => }
    decls(4) should matchPattern { case Declaration("f", TypeFile(_), None, _)      => }
    decls(5) should matchPattern { case Declaration("d", TypeDirectory(_), None, _) => }
  }

  it should "parse a complex workflow with imports" in {
    val doc = getDocument(getSource("movies.wdl"))

    doc.version.value shouldBe WdlVersion.V2
    doc.elements.size shouldBe 1
    doc.elements(0) shouldBe a[ImportDoc]
    doc.workflow shouldBe defined
  }

  it should "parse a task with complex hints" in {
    getDocument(getSource("complex_hint_values.wdl"))
  }

  it should "handle passthrough syntax for call inputs" in {
    val doc = getDocument(getSource("passthrough.wdl"))
    doc.workflow.get.body match {
      case Vector(Call(_, _, _, Some(inputs), _)) =>
        inputs.value.size shouldBe 1
        inputs.value.head.expr should matchPattern {
          case ExprIdentifier("s", _) =>
        }
      case _ => throw new Exception("unexpected inputs")
    }
  }

  it should "handle comments in meta sections" in {
    val doc = getDocument(getSource("parameter_meta_comment.wdl"))
    doc.comments(10) shouldBe Comment("#foo", SourceLocation(doc.source, 10, 4, 10, 8))
  }
}
