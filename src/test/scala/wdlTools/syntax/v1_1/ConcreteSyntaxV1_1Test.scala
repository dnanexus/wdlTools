package wdlTools.syntax.v1_1

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.v1_1.ConcreteSyntax.{
  Document,
  ExprInt,
  ExprMember,
  ExprObjectLiteral,
  ExprString,
  Task
}

import java.nio.file.Paths

class ConcreteSyntaxV1_1Test extends AnyFlatSpec with Matchers {
  private val sourcePath = Paths.get(getClass.getResource("/syntax/v1_1").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(sourcePath))
  private val logger = Logger.Quiet

  private def getDocument(fname: String): Document = {
    ParseTop(
        WdlV1_1Grammar.newInstance(fileResolver.fromPath(sourcePath.resolve(fname)),
                                   Vector.empty,
                                   logger = logger)
    ).parseDocument
  }

  it should "parse sep option and sep function" in {
    getDocument("sep.wdl")
  }

  it should "parse nested expressions" in {
    getDocument("nested_expr.wdl")
  }

  it should "parse a task with custom runtime attribute" in {
    val doc = getDocument("custom_runtime.wdl")
    val tasks = doc.elements.collect {
      case t: Task => t
    }
    tasks.size shouldBe 1
    val runtime = tasks.head.runtime.get
    val kvmap = runtime.kvs.map(kv => kv.id -> kv.expr).toMap
    kvmap.keySet shouldBe Set("dx_timeout", "dx_instance_type", "dx_restart")
    kvmap("dx_restart") should matchPattern {
      case ExprObjectLiteral(
          Vector(
              ExprMember(ExprString("max", _), ExprInt(1)),
              ExprMember(ExprString("default", _), ExprInt(2))
          )
          ) =>
    }
  }
}
