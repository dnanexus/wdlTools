package wdlTools.typechecker

//import collection.JavaConverters._
import java.nio.file.{Path, Paths}
import org.scalatest.{FlatSpec, Matchers}

import wdlTools.util.Util.Conf
import wdlTools.syntax.{AbstractSyntax => AST}

class CheckerTest extends FlatSpec with Matchers {

  private lazy val wdlSourceDirs: Vector[Path] = {
    val p1: Path = Paths.get(getClass.getResource("/workflows").getPath)
    val p2: Path = Paths.get(getClass.getResource("/tasks").getPath)
    Vector(p1, p2)
  }
  private val conf = Conf(antlr4Trace = false, localDirectories = wdlSourceDirs)
  private val stdlib = Stdlib(conf)
  private val checker = new Checker(stdlib, conf)

  /*  private def getWdlSource(dirname: String, fname: String): String = {
    val p: String = getClass.getResource(s"/${dirname}/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }*/

  it should "type check simple declarations" in {
    val decl = AST.Declaration("a", AST.TypeInt, None)
    checker.apply(decl, Map.empty)

    val decl2 = AST.Declaration("a", AST.TypeInt, Some(AST.ValueInt(13)))
    checker.apply(decl2, Map.empty)
  }
}
