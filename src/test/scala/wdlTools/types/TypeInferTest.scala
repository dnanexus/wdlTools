package wdlTools.types

import java.nio.file.{Path, Paths}

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.syntax.Parsers
import wdlTools.types.{TypedAbstractSyntax => TAT}

import scala.collection.Map

class TypeInferTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Normal
  private val v1Dir = Paths.get(getClass.getResource("/types/v1").getPath)
  private val v1StructsDir =
    Paths.get(getClass.getResource("/types/v1/structs").getPath)
  private val v2StructsDir =
    Paths.get(getClass.getResource("/types/v2/structs").getPath)

  private def check(dir: Path, file: String): TAT.Document = {
    val fileResolver = FileSourceResolver.create(Vector(dir))
    val checker = TypeInfer(fileResolver = fileResolver, logger = logger)
    val sourceFile = fileResolver.fromPath(dir.resolve(file))
    val doc = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)._1
  }

  it should "handle several struct definitions" taggedAs Edge in {
    check(v1StructsDir, "file3.wdl")
  }

  it should "handle struct aliases" in {
    check(v2StructsDir, "parent_workflow.wdl")
  }

  it should "handle struct literals" in {
    val tDoc = check(v2StructsDir, "struct_literal.wdl")
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

  it should "compare identical callables as equal" in {
    val tDoc = check(v1Dir, "top_level.wdl")
    val topImports = tDoc.elements.collect {
      case TAT.ImportDoc(namespace, _, _, doc, _) => namespace -> doc
    }.toMap
    topImports.size shouldBe 2
    val subImports = topImports("sub").elements.collect {
      case TAT.ImportDoc(namespace, _, _, doc, _) => namespace -> doc
    }.toMap
    subImports.size shouldBe 1
    val topTasks = topImports("tasks").elements.collect {
      case task: TAT.Task => task
    }
    topTasks.size shouldBe 1
    val subTasks = subImports("tasks").elements.collect {
      case task: TAT.Task => task
    }
    subTasks.size shouldBe 1
    topTasks.head shouldBe subTasks.head
  }
}
