package wdlTools.types

import java.nio.file.{Path, Paths}

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.Parsers
import wdlTools.types.{TypedAbstractSyntax => TAT}

import scala.collection.immutable.TreeSeqMap

class TypeUtilsTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Quiet
  private val graphDir = Paths.get(getClass.getResource("/types/graph").getPath)

  private def check(dir: Path, file: String): TAT.Document = {
    val fileResolver = FileSourceResolver.create(Vector(dir))
    val checker =
      TypeInfer(fileResolver = fileResolver, logger = logger)
    val sourceFile = fileResolver.fromPath(dir.resolve(file))
    val doc = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)._1
  }

  it should "build workflow body element blocks" in {
    val doc = check(graphDir, "nested_no_fw_ref.wdl")
    val body = WorkflowBodyElements(doc.workflow.get.body)

    body.inputs shouldBe Map(
        "name" -> WdlTypes.T_String,
        "i" -> WdlTypes.T_Int
    )
    body.outputs shouldBe Map(
        "strings" -> WdlTypes.T_Array(WdlTypes.T_String),
        "jarray" -> WdlTypes.T_Array(WdlTypes.T_Optional(WdlTypes.T_Int)),
        "greeting" -> WdlTypes.T_String,
        "r" -> WdlTypes.T_Array(WdlTypes.T_Int),
        "jdef" -> WdlTypes.T_Array(WdlTypes.T_Int),
        "s" -> WdlTypes.T_Array(WdlTypes.T_String),
        "b" -> WdlTypes.T_Array(WdlTypes.T_Boolean),
        "j" -> WdlTypes.T_Array(WdlTypes.T_Optional(WdlTypes.T_Int)),
        "bar" -> WdlTypes.T_Array(
            WdlTypes.T_Optional(
                WdlTypes.T_Call("bar",
                                TreeSeqMap(
                                    "out" -> WdlTypes.T_Int
                                ))
            )
        ),
        "bar.out" -> WdlTypes.T_Array(WdlTypes.T_Optional(WdlTypes.T_Int))
    )

    body.privateVariables.size shouldBe 5
    body.calls.size shouldBe 0
    body.conditionals.size shouldBe 0
    body.scatterVars.isEmpty shouldBe true
    body.scatters.size shouldBe 1

    val scatter = body.scatters(0)
    scatter.inputs shouldBe Map(
        "r" -> WdlTypes.T_Array(WdlTypes.T_Int),
        "greeting" -> WdlTypes.T_String,
        "name" -> WdlTypes.T_String
    )
    scatter.outputs shouldBe Map(
        "s" -> WdlTypes.T_Array(WdlTypes.T_String),
        "b" -> WdlTypes.T_Array(WdlTypes.T_Boolean),
        "j" -> WdlTypes.T_Array(WdlTypes.T_Optional(WdlTypes.T_Int)),
        "bar" -> WdlTypes.T_Array(
            WdlTypes.T_Optional(
                WdlTypes.T_Call("bar",
                                TreeSeqMap(
                                    "out" -> WdlTypes.T_Int
                                ))
            )
        ),
        "bar.out" -> WdlTypes.T_Array(WdlTypes.T_Optional(WdlTypes.T_Int))
    )
    scatter.bodyElements.scatterVars shouldBe Set("x")

    scatter.bodyElements.privateVariables.size shouldBe 2
    scatter.bodyElements.calls.size shouldBe 0
    scatter.bodyElements.scatters.size shouldBe 0
    scatter.bodyElements.conditionals.size shouldBe 1

    val cond = scatter.bodyElements.conditionals(0)
    cond.scatterVars shouldBe Set("x")
    cond.inputs shouldBe Map("b" -> WdlTypes.T_Boolean)
    cond.outputs shouldBe Map(
        "j" -> WdlTypes.T_Optional(WdlTypes.T_Int),
        "bar" ->
          WdlTypes.T_Optional(
              WdlTypes.T_Call("bar",
                              TreeSeqMap(
                                  "out" -> WdlTypes.T_Int
                              ))
          ),
        "bar.out" -> WdlTypes.T_Optional(WdlTypes.T_Int)
    )

    cond.bodyElements.privateVariables.size shouldBe 1
    cond.bodyElements.calls.size shouldBe 1
    cond.bodyElements.conditionals.size shouldBe 0
    cond.bodyElements.scatters.size shouldBe 0
  }
}
