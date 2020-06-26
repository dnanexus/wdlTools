package wdlTools.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UtilTest extends AnyFlatSpec with Matchers {
  val sentence = "I am major major"

  it should "Correctly compress and decompress" in {
    val s2 = Util.gzipDecompress(Util.gzipCompress(sentence.getBytes))
    sentence should be(s2)
  }

  it should "Correctly encode and decode base64" in {
    val encodeDecode = Util.base64DecodeAndGunzip(Util.gzipAndBase64Encode(sentence))
    sentence should be(encodeDecode)
  }
}
