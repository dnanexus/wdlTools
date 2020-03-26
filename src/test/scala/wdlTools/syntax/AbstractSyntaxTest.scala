package wdlTools.syntax

import AbstractSyntax._
import collection.JavaConverters._
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
import wdlTools.util.Options

class AbstractSyntaxTest extends FlatSpec with Matchers {

  private lazy val wdlSourceDirs: Vector[Path] = {
    val p1: Path = Paths.get(getClass.getResource("/syntax/workflows").getPath)
    val p2: Path = Paths.get(getClass.getResource("/syntax/tasks").getPath)
    Vector(p1, p2)
  }
  private lazy val conf = Options(antlr4Trace = false, localDirectories = wdlSourceDirs)

  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/syntax/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  it should "handle import statements" in {
    val pa = new ParseAll(conf)
    val doc = pa.apply(getWdlSource("workflows", "imports.wdl"))

    doc.version shouldBe ("1.0")

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe (2)

    doc.workflow should not be empty
  }
}
