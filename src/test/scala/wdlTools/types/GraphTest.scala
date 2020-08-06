package wdlTools.types

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import scalax.collection.GraphEdge.DiEdge
import wdlTools.syntax.Parsers
import wdlTools.types.ExprGraph.TaskVarKind
import wdlTools.types.TypedAbstractSyntax.Task
import wdlTools.util.{FileSourceResolver, Logger}

class GraphTest extends AnyFlatSpec with Matchers {
  private val fileResolver = FileSourceResolver.create(
      Vector(Paths.get(getClass.getResource("/types/graph").getPath))
  )
  private val logger = Logger.Normal
  private val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val typeInfer = TypeInfer(fileResolver = fileResolver, logger = logger)

  it should "order a graph" in {
    val graph: Graph[String, DiEdge] = Graph("a" ~> "b", "b" ~> "c", "d" ~> "a")
    GraphUtils.toOrderedVector(graph, "d") shouldBe Vector("d", "a", "b", "c")
    GraphUtils.toOrderedVector(graph, "a") shouldBe Vector("a", "b", "c")
  }

  it should "build an expression graph" in {
    val fileSource = fileResolver.resolve("tasks.wdl")
    val doc = parsers.parseDocument(fileSource)
    val (tDoc, _) = typeInfer.apply(doc)
    val tasks = tDoc.elements.collect {
      case t: Task => t
    }
    tasks.size shouldBe 1
    val (graph, variables) = ExprGraph.buildFromTask(tasks.head)
    val ordered = GraphUtils.toOrderedVector(graph)
    ordered.size shouldBe 9
    // there are mutliple equally valid sortings - we just need to make sure
    // the contents of each 'block' are the same and the blocks are ordered
    val expected: Vector[Set[String]] = Vector(
        Set("f", "i", "name", "d"),
        Set("s", "x", "dout"),
        Set("dout2", "sout")
    )
    expected.foldLeft(0) {
      case (start, ex) =>
        val end = start + ex.size
        val slice: Set[String] = ordered.slice(start, end).toSet
        slice shouldBe ex
        end
    }
    variables.foreach {
      case ("b", info) =>
        info.referenced shouldBe false
        info.kind shouldBe Some(TaskVarKind.Input)
      case ("y", info) =>
        info.referenced shouldBe false
        info.kind shouldBe None
      case ("f" | "s" | "i" | "d" | "name", info) =>
        info.referenced shouldBe true
        info.kind shouldBe Some(TaskVarKind.Input)
      case ("x", info) =>
        info.referenced shouldBe true
        info.kind shouldBe Some(TaskVarKind.PreCommand)
      case ("dout", info) =>
        info.referenced shouldBe true
        info.kind shouldBe Some(TaskVarKind.PostCommand)
      case ("dout2" | "sout", info) =>
        info.referenced shouldBe true
        info.kind shouldBe Some(TaskVarKind.Output)
      case other =>
        throw new Exception(s"invalid var ${other}")
    }
  }
}
