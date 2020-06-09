package wdlTools.eval

import java.nio.file.{Files, Path, Paths}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import wdlTools.eval.EvalConfig
import wdlTools.syntax.TextSource
import wdlTools.types.{TypeCheckingRegime, TypeOptions}
import wdlTools.util.Verbosity

class IoSuppTest extends AnyFlatSpec with Matchers {
  private val srcDir = Paths.get(getClass.getResource("/eval").getPath)
  private val opts =
    TypeOptions(typeChecking = TypeCheckingRegime.Lenient,
                antlr4Trace = false,
                localDirectories = Vector(srcDir),
                verbosity = Verbosity.Normal)

  def safeMkdir(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    } else {
      // Path exists, make sure it is a directory, and not a file
      if (!Files.isDirectory(path))
        throw new Exception(s"Path ${path} exists, but is not a directory")
    }
  }

  case object DxProtocol extends FileAccessProtocol {
    val prefixes = Vector("dx")
    def size(dxFilePath: String): Long = ???
    def readFile(path: String): String = ???
  }

  private lazy val evalCfg: EvalConfig = {
    val baseDir = Paths.get("/tmp/evalTest")
    val homeDir = baseDir.resolve("home")
    val tmpDir = baseDir.resolve("tmp")
    for (d <- Vector(baseDir, homeDir, tmpDir))
      safeMkdir(d)
    val stdout = baseDir.resolve("stdout")
    val stderr = baseDir.resolve("stderr")
    EvalConfig.make(homeDir, tmpDir, stdout, stderr, Vector(DxProtocol))
  }

  private val ioSupp = new IoSupp(opts, evalCfg, None)
  private val dummyTextSource = TextSource(0, 0, 0, 0)

  it should "Figure out protocols" in {
    val proto =
      ioSupp.figureOutProtocol("dx://file-FGqFJ8Q0ffPGVz3zGy4FK02P:://fileB", dummyTextSource)
    proto.prefixes shouldBe (Vector("dx"))
  }

  it should "Recognize http" in {
    // recognize http
    val proto = ioSupp.figureOutProtocol("http://A.txt", dummyTextSource)
    proto.prefixes.iterator sameElements Vector("http", "https")

    val proto2 = ioSupp.figureOutProtocol("https://A.txt", dummyTextSource)
    proto2.prefixes.iterator sameElements Vector("http", "https")
  }

  it should "Recognize local files" in {
    // recognize local file access
    val proto = ioSupp.figureOutProtocol("file://A.txt", dummyTextSource)
    proto.prefixes.iterator sameElements Vector("", "file")

    val proto2 = ioSupp.figureOutProtocol("A.txt", dummyTextSource)
    proto2.prefixes.iterator sameElements Vector("", "file")

  }
}
