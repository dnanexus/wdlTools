package wdlTools.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.SortedSet

class EnumTest extends AnyFlatSpec with Matchers {
  object Foo extends Enum {
    type Foo = Value
    val BAZ, Bar, blorf = Value
  }

  it should "find enum value ignoring case" in {
    Foo.withNameIgnoreCase("bar") shouldBe Foo.Bar
    Foo.withNameIgnoreCase("baz") shouldBe Foo.BAZ
    Foo.withNameIgnoreCase("blorf") shouldBe Foo.blorf
    Foo.withNameIgnoreCase("BAR") shouldBe Foo.Bar
    Foo.withNameIgnoreCase("BAZ") shouldBe Foo.BAZ
    Foo.withNameIgnoreCase("BLORF") shouldBe Foo.blorf
  }

  it should "have names" in {
    Foo.names shouldBe SortedSet("BAZ", "Bar", "blorf")
  }
}
