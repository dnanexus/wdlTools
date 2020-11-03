package wdlTools.types

import java.nio.file.Paths

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.syntax.Parsers
import wdlTools.types.{TypedAbstractSyntax => TAT}

class TypeInferTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Normal
  //private val v1Dir = Paths.get(getClass.getResource("/types/v1").getPath)
  private val v1StructsDir =
    Paths.get(getClass.getResource("/types/v1/structs").getPath)
  private val v2StructsDir =
    Paths.get(getClass.getResource("/types/v2/structs").getPath)

  it should "handle several struct definitions" taggedAs Edge in {
    val structsFileResolver = FileSourceResolver.create(Vector(v1StructsDir))
    val checker = TypeInfer(fileResolver = structsFileResolver, logger = logger)
    val sourceFile = structsFileResolver.fromPath(v1StructsDir.resolve("file3.wdl"))
    val doc = Parsers(followImports = true, fileResolver = structsFileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)
  }

  it should "handle struct aliases" in {
    val structsFileResolver = FileSourceResolver.create(Vector(v2StructsDir))
    val checker = TypeInfer(fileResolver = structsFileResolver, logger = logger)
    val sourceFile = structsFileResolver.fromPath(v2StructsDir.resolve("parent_workflow.wdl"))
    val doc = Parsers(followImports = true, fileResolver = structsFileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)
  }

  it should "handle struct literals" in {
    val structsFileResolver = FileSourceResolver.create(Vector(v2StructsDir))
    val checker = TypeInfer(fileResolver = structsFileResolver, logger = logger)
    val sourceFile = structsFileResolver.fromPath(v2StructsDir.resolve("struct_literal.wdl"))
    val doc = Parsers(followImports = true, fileResolver = structsFileResolver, logger = logger)
      .parseDocument(sourceFile)
    val (tDoc, _) = checker.apply(doc)
    val task = tDoc.elements.collect {
      case task: TAT.Task => task
    }.head
    task.outputs.size shouldBe 1
    task.outputs.head match {
      case TAT.OutputParameter("name", WdlTypes.T_Struct("Name", types), expr, _) =>
        val typesMap = Map(
            "first" -> WdlTypes.T_String,
            "last" -> WdlTypes.T_String
        )
        types shouldBe typesMap
        expr match {
          case TAT.ExprObject(members, WdlTypes.T_Struct("Name", types), _) =>
            types shouldBe typesMap
            val objMembers = members.map {
              case (TAT.ValueString(key, WdlTypes.T_String, _), value) => key -> value
              case other =>
                throw new Exception(s"invalid object key ${other}")
            }
            objMembers.keySet shouldBe Set("first", "last")
            objMembers("first") should matchPattern {
              case TAT.ExprIdentifier("first", WdlTypes.T_String, _) =>
            }
            objMembers("last") should matchPattern {
              case TAT.ExprIdentifier("last", WdlTypes.T_String, _) =>
            }
          case _ =>
            throw new Exception("invalid expression for output 'name'")
        }
      case _ =>
        throw new Exception("invalid OutputParameter for 'name'")
    }
  }
}
