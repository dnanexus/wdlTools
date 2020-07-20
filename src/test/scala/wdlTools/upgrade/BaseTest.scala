package wdlTools.upgrade

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.generators.code
import wdlTools.syntax.WdlVersion
import wdlTools.util.{FileSource, FileSourceResolver, FileUtils}

class BaseTest extends AnyFlatSpec with Matchers {
  def getBeforePath(fname: String): FileSource = {
    val path =
      FileUtils.absolutePath(Paths.get(getClass.getResource(s"/upgrade/before/${fname}").getPath))
    FileSourceResolver.get.fromPath(path)
  }

  def getAfterPath(fname: String): FileSource = {
    val path =
      FileUtils.absolutePath(Paths.get(getClass.getResource(s"/upgrade/after/${fname}").getPath))
    FileSourceResolver.get.fromPath(path)
  }

  def getBeforeAfterPair(fname: String): (FileSource, FileSource) = {
    (getBeforePath(fname), getAfterPath(fname))
  }

  it should "Upgrade draft-2 to v1.0" in {
    val (beforeUri, afterPath) = getBeforeAfterPair("simple.wdl")
    val upgrader = code.Upgrader()
    val documents = upgrader.upgrade(beforeUri, Some(WdlVersion.Draft_2), WdlVersion.V1)
    documents(beforeUri).mkString("\n") shouldBe afterPath.readString
  }
}
