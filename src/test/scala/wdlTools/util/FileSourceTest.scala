package wdlTools.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FileSourceTest extends AnyFlatSpec with Matchers {
  case object DxProtocol extends FileAccessProtocol {
    val prefixes = Vector("dx")
    override def resolve(uri: String): FileSource = ???
  }
  private val resolver = FileSourceResolver.create(userProtocols = Vector(DxProtocol))

  it should "Figure out protocols" in {
    // this style of uri will have authority='file-FGqFJ8Q0ffPGVz3zGy4FK02P::' and path='//fileB'
    val proto = resolver.getProtocol("dx://file-FGqFJ8Q0ffPGVz3zGy4FK02P:://fileB")
    proto.prefixes shouldBe Vector("dx")
  }

  it should "Recognize http" in {
    // recognize http
    val proto = resolver.getProtocol("http://A.txt")
    proto.prefixes.iterator sameElements Vector("http", "https")

    val proto2 = resolver.getProtocol("https://A.txt")
    proto2.prefixes.iterator sameElements Vector("http", "https")
  }

  it should "Recognize local files" in {
    // recognize local file access
    val proto = resolver.getProtocol("file:///A.txt")
    proto.prefixes.iterator sameElements Vector("", "file")
  }
}
