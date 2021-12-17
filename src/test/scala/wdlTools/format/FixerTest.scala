package wdlTools.format

import dx.util.{FileNode, FileSourceResolver}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.generators.code.Fixer

import java.nio.file.{Files, Path, Paths}

class FixerTest extends AnyFlatSpec with Matchers {
  def getWdlPath(fname: String, subdir: String): Path = {
    Paths.get(getClass.getResource(s"/format/${subdir}/${fname}").getPath)
  }

  private def getWdlSource(fname: String, subdir: String): FileNode = {
    FileSourceResolver.get.fromPath(getWdlPath(fname, subdir))
  }

  private def fix(name: String, validate: Boolean): Unit = {
    val fixer = Fixer()
    val docSource = getWdlSource(name, "before")
    val outputDir = Files.createTempDirectory("fix").toRealPath()
    outputDir.toFile.deleteOnExit()
    val fixedSource = fixer.fix(docSource, outputDir)
    if (validate) {
      val afterSource = getWdlSource(name, "after")
      fixedSource.readString shouldBe afterSource.readString
    }
  }

  it should "fix common invalid WDL usages" in {
    fix("fix.wdl", validate = true)
  }
}
