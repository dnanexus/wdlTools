package wdlTools.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UtilTest extends AnyFlatSpec with Matchers {
  it should "Correctly replace file suffix" in {
    Util.replaceFileSuffix("foo.bar.baz", ".blorf") shouldBe "foo.bar.blorf"
  }
}
