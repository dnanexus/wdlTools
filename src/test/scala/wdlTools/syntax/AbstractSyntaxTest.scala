package wdlTools.syntax

import AbstractSyntax._
import java.nio.file.{Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.{Options, SourceCode, URL}

class AbstractSyntaxTest extends FlatSpec with Matchers {
  private lazy val wdlSourceDirs: Vector[Path] = {
    val p1: Path = Paths.get(getClass.getResource("/syntax/v1_0/workflows").getPath)
    val p2: Path = Paths.get(getClass.getResource("/syntax/v1_0/tasks").getPath)
    Vector(p1, p2)
  }
  private lazy val conf = Options(antlr4Trace = false, localDirectories = wdlSourceDirs)
  private lazy val loader = SourceCode.Loader(conf)
  private lazy val parser = ParseAll(conf, loader)

  private def getWdlSource(dirname: String, fname: String): SourceCode = {
    val p: String = getClass.getResource(s"/syntax/v1_0/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    loader.apply(URL(path.toString))
  }

  it should "handle import statements" in {
    val doc = parser.apply(getWdlSource("workflows", "imports.wdl"))

    doc.version shouldBe WdlVersion.V1_0

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe 2

    doc.workflow should not be empty
  }
}
