package wdlTools.types

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import scalax.collection.GraphEdge.DiEdge
import wdlTools.syntax.Parsers
import wdlTools.types.ExprGraph.VarKind
import wdlTools.types.TypedAbstractSyntax.Task
import wdlTools.types.WdlTypes._
import dx.util.{FileSourceResolver, Logger}

import scala.collection.immutable.TreeSeqMap

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

  def compareOrdered(expected: Vector[Set[String]], ordered: Vector[String]): Unit = {
    expected.foldLeft(0) {
      case (start, ex) =>
        val end = start + ex.size
        val slice: Set[String] = ordered.slice(start, end).toSet
        slice shouldBe ex
        end
    }
  }

  it should "build an expression graph" in {
    val fileSource = fileResolver.resolve("tasks.wdl")
    val doc = parsers.parseDocument(fileSource)
    val (tDoc, _) = typeInfer.apply(doc)
    val tasks = tDoc.elements.collect {
      case t: Task => t
    }
    tasks.size shouldBe 1
    val exprGraph = ExprGraph.buildFrom(tasks.head)
    val ordered = exprGraph.dependencyOrder
    ordered.size shouldBe 9
    // there are mutliple equally valid sortings - we just need to make sure
    // the contents of each 'block' are the same and the blocks are ordered
    val expected: Vector[Set[String]] = Vector(
        Set("f", "i", "name", "d"),
        Set("s", "x", "dout"),
        Set("dout2", "sout")
    )
    compareOrdered(expected, ordered)
    exprGraph.varInfo.foreach {
      case ("b", info) =>
        info.referenced shouldBe false
        info.kind shouldBe VarKind.Input
      case ("y", info) =>
        info.referenced shouldBe false
        info.kind shouldBe VarKind.Private
      case ("f" | "s" | "i" | "d" | "name", info) =>
        info.referenced shouldBe true
        info.kind shouldBe VarKind.Input
      case ("x", info) =>
        info.referenced shouldBe true
        info.kind shouldBe VarKind.Private
      case ("dout", info) =>
        info.referenced shouldBe true
        info.kind shouldBe VarKind.PostCommand
      case ("dout2" | "sout", info) =>
        info.referenced shouldBe true
        info.kind shouldBe VarKind.Output
      case other =>
        throw new Exception(s"invalid var ${other}")
    }
  }

  it should "build a type graph" in {
    val struct1 = T_Struct(
        "Foo",
        TreeSeqMap(
            "bar" -> T_Int,
            "baz" -> T_String
        )
    )
    val struct2 = T_Struct(
        "Bork",
        TreeSeqMap(
            "bink" -> struct1,
            "bonk" -> T_Float
        )
    )
    val struct3 = T_Struct(
        "Bloke",
        TreeSeqMap(
            "boop" -> T_Array(struct1),
            "bip" -> T_Optional(struct2)
        )
    )
    val struct4 = T_Struct(
        "Boom",
        TreeSeqMap(
            "bop" -> T_Map(struct2, struct3)
        )
    )
    val struct5 = T_Struct(
        "Bump",
        TreeSeqMap(
            "bap" -> T_String
        )
    )
    // expect root -> 1; root -> 5; 1 -> 2, 3; 2 -> 3, 4
    val types = Vector(struct1, struct2, struct3, struct4, struct5).map(s => s.name -> s).toMap
    val graph = TypeGraph.buildFromStructTypes(types)
    val ordered = GraphUtils.toOrderedVector(graph)
    val expected = Vector(
        Set("Foo", "Bump"),
        Set("Bork"),
        Set("Bloke"),
        Set("Boom")
    )
    compareOrdered(expected, ordered)

    // now use some aliases
    val struct3withAliases = T_Struct(
        "Bloke",
        TreeSeqMap(
            "boop" -> T_Array(struct1),
            "bip" -> T_Optional(struct2.copy(name = "alias2"))
        )
    )
    val struct4withAliases = T_Struct(
        "Boom",
        TreeSeqMap(
            "bop" -> T_Map(struct2.copy(name = "alias2"), struct3.copy(name = "alias3"))
        )
    )
    val typesWithAliases = Map(
        "Foo" -> struct1,
        "alias2" -> struct2,
        "alias3" -> struct3withAliases,
        "Boom" -> struct4withAliases,
        "Bump" -> struct5
    )
    val graphWithAliases = TypeGraph.buildFromStructTypes(typesWithAliases)
    val orderedWithAliases = GraphUtils.toOrderedVector(graphWithAliases)
    val expectedWithAliases = Vector(
        Set("Foo", "Bump"),
        Set("alias2"),
        Set("alias3"),
        Set("Boom")
    )
    compareOrdered(expectedWithAliases, orderedWithAliases)
  }

  it should "build an expression graph from WDL file" in {
    val fileSource = fileResolver.resolve("many_structs.wdl")
    val doc = parsers.parseDocument(fileSource)
    val (_, ctx) = typeInfer.apply(doc)
    val graph = TypeGraph.buildFromStructTypes(ctx.aliases.toMap)
    val ordered = GraphUtils.toOrderedVector(graph)
    val expected = Vector(
        Set("Coord", "Bunk", "Foo", "SampleReports"),
        Set("SampleReportsArray")
    )
    compareOrdered(expected, ordered)
  }
}
