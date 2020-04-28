package wdlTools.eval

import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.v1.ParseAll
import wdlTools.util.{ExprEvalConfig, Options, Util, Verbosity}
import wdlTools.typing.{Stdlib => TypeStdlib, TypeChecker}

class EvalTest extends FlatSpec with Matchers {
  private val srcDir = Paths.get(getClass.getResource("/eval/v1").getPath)
  private val opts =
    Options(antlr4Trace = false,
            localDirectories = Vector(srcDir),
            verbosity = Verbosity.Quiet)
  private val parser = ParseAll(opts)
  private val stdlib = TypeStdlib(opts)
  private val checker = TypeChecker(stdlib)

  def safeMkdir(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    } else {
      // Path exists, make sure it is a directory, and not a file
      if (!Files.isDirectory(path))
        throw new Exception(s"Path ${path} exists, but is not a directory")
    }
  }

  private lazy val evalCfg : ExprEvalConfig = {
    val baseDir = Paths.get("/tmp/evalTest")
    val homeDir = baseDir.resolve("home")
    val tmpDir = baseDir.resolve("tmp")
    for (d <- Vector(baseDir, homeDir, tmpDir))
      safeMkdir(d)
    val stdout = baseDir.resolve("stdout")
    val stderr = baseDir.resolve("stderr")
    ExprEvalConfig(homeDir,
                   tmpDir,
                   stdout,
                   stderr)
  }

  def parseAndTypeCheck(file : Path) : AST.Document = {
    val doc = parser.parseDocument(Util.pathToURL(file))
    checker.apply(doc)
    doc
  }

  // ignore a value without causing a compilation error
  def ignore[A](x : A) : Unit = {}

  it should "handle simple expressions" in {
    val file = srcDir.resolve("simple_expr.wdl")
    val doc = parseAndTypeCheck(file)
    val evaluator = EvalExpr(opts, evalCfg, Some(opts.getURL(file.toString)))

    doc.workflow should not be empty
    val wf = doc.workflow.get

    val decls : Vector[AST.Declaration] = wf.body.collect{
      case x : AST.Declaration => x
    }.toVector
    for (decl <- decls) {
      val wdlValue = evaluator.apply(decl.expr.get)
      ignore(wdlValue)
    }
  }
}
