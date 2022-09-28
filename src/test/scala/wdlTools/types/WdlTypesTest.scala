package wdlTools.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.types.WdlTypes.T_Struct

import scala.collection.immutable.SeqMap

class WdlTypesTest extends AnyFlatSpec with Matchers {
  "T_Struct" should "walk the keys" in {
    val struct = T_Struct(
        "House",
        SeqMap(
            "num_floors" -> WdlTypes.T_Int,
            "neighbors" -> T_Struct(
                "Neighbors",
                SeqMap(
                    "JohnDoe" -> WdlTypes.T_String,
                    "JaneDoe" -> WdlTypes.T_String
                )
            ),
            "street" -> WdlTypes.T_String
        )
    )
    val flat = struct.flattenMembers()
    flat shouldBe SeqMap(
        "num_floors" -> WdlTypes.T_Int,
        "neighbors.JohnDoe" -> WdlTypes.T_String,
        "neighbors.JaneDoe" -> WdlTypes.T_String,
        "street" -> WdlTypes.T_String
    )
  }
}
