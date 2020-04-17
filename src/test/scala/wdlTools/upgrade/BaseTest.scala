package wdlTools.upgrade

import java.net.URL
import java.nio.file.{Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.formatter.Upgrader
import wdlTools.syntax.WdlVersion
import wdlTools.util.{Options, Util}

class BaseTest extends FlatSpec with Matchers {
  private lazy val opts = Options()

  def getBeforePath(fname: String): Path = {
    Paths.get(getClass.getResource(s"/upgrade/before/${fname}").getPath)
  }

  def getAfterPath(fname: String): Path = {
    Paths.get(getClass.getResource(s"/upgrade/after/${fname}").getPath)
  }

  def getBeforeAfterPair(fname: String): (URL, Path) = {
    (Util.getURL(getBeforePath(fname)), getAfterPath(fname))
  }

  it should "Upgrade draft-2 to v1.0" in {
    val (beforeURL, afterPath) = getBeforeAfterPair("simple.wdl")
    val expected = Util.readFromFile(afterPath)
    val upgrader = Upgrader(opts)
    val documents = upgrader.upgrade(beforeURL, Some(WdlVersion.Draft_2), WdlVersion.V1)
    documents(beforeURL).mkString("\n") shouldBe expected
  }
}
