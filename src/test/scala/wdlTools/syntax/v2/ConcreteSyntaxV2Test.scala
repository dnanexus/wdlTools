package wdlTools.syntax.v2

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.WdlVersion
import wdlTools.syntax.v2.ConcreteSyntax._
import dx.util.{FileNode, FileSourceResolver, Logger}

class ConcreteSyntaxV2Test extends AnyFlatSpec with Matchers {
  private val sourcePath = Paths.get(getClass.getResource("/syntax/v2").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(sourcePath))
  private val logger = Logger.Quiet

  private def getSource(fname: String): FileNode = {
    fileResolver.fromPath(sourcePath.resolve(fname))
  }

  private def getDocument(fileSource: FileNode): Document = {
    ParseTop(WdlV2Grammar.newInstance(fileSource, Vector.empty, logger = logger)).parseDocument
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

  it should "parse multiline strings" in {
    val doc = getDocument(getSource("multiline_string.wdl"))
    val task = doc.elements.head match {
      case task: Task => task
      case _          => throw new Exception("not a task")
    }
    task.input.get.declarations.head.expr.get match {
      case ExprCompoundString(parts, loc) =>
        parts.map {
          case ExprString(s, _) => s
          case _                => throw new Exception("expected ExprString")
        } shouldBe Vector(
            "This is a",
            " ",
            "multiline string"
        )
        loc.line shouldBe 5
        loc.col shouldBe 15
        loc.endLine shouldBe 6
        loc.endCol shouldBe 37
      case _ =>
        throw new Exception("expected ExprString")
    }
    task.declarations(0).expr.get match {
      case ExprCompoundString(parts, loc) =>
        parts.map {
          case ExprString(s, _) => s
          case _                => throw new Exception("expected ExprString")
        } shouldBe Vector(
            "This is a",
            "\n",
            "multiline string with a margin"
        )
        loc.line shouldBe 9
        loc.col shouldBe 13
        loc.endLine shouldBe 10
        loc.endCol shouldBe 49
      case _ =>
        throw new Exception("expected ExprString")
    }
    task.declarations(1).expr.get match {
      case ExprCompoundString(parts, loc) =>
        parts.map {
          case ExprString(s, _) => s
          case _                => throw new Exception("expected ExprString")
        } shouldBe Vector(
            "This is a",
            "\n                ",
            "multiline string with an indent"
        )
        loc.line shouldBe 12
        loc.col shouldBe 13
        loc.endLine shouldBe 13
        loc.endCol shouldBe 50
      case _ =>
        throw new Exception("expected ExprString")
    }
    task.meta.get.kvs(0).value match {
      case MetaValueString(s, loc) =>
        s shouldBe "This is a multiline string"
        loc.line shouldBe 31
        loc.col shouldBe 7
        loc.endLine shouldBe 32
        loc.endCol shouldBe 29
      case _ =>
        throw new Exception("expected MetaValueString")
    }
    task.meta.get.kvs(1).value match {
      case MetaValueString(s, loc) =>
        s shouldBe "This is a\nmultiline string with a margin"
        loc.line shouldBe 33
        loc.col shouldBe 7
        loc.endLine shouldBe 34
        loc.endCol shouldBe 43
      case _ =>
        throw new Exception("expected MetaValueString")
    }
    task.meta.get.kvs(2).value match {
      case MetaValueString(s, loc) =>
        s shouldBe "This is a\n          multiline string with an indent"
        loc.line shouldBe 35
        loc.col shouldBe 7
        loc.endLine shouldBe 36
        loc.endCol shouldBe 44
      case _ =>
        throw new Exception("expected MetaValueString")
    }
  }
}
