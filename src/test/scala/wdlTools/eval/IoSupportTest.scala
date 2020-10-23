package wdlTools.eval

import java.nio.file.{Files, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.util.{
  AddressableFileNode,
  FileAccessProtocol,
  FileSourceResolver,
  FileUtils,
  Logger,
  StringFileNode
}

class IoSupportTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval").getPath)
  private val logger = Logger.Normal

  case object DxProtocol extends FileAccessProtocol {
    val schemes = Vector("dx")
    override def resolve(address: String): AddressableFileNode = ???
  }

  private def setup(): (EvalPaths, FileSourceResolver) = {
    val baseDir = Files.createTempDirectory("eval")
    baseDir.toFile.deleteOnExit()
    val tmpDir = baseDir.resolve("tmp")
    val evalPaths = DefaultEvalPaths(baseDir, tmpDir)
    val fileResolver =
      FileSourceResolver.create(Vector(srcDir, evalPaths.getWorkDir()), Vector(DxProtocol))
    (evalPaths, fileResolver)
  }

  private val placeholderSourceLocation = SourceLocation.empty

  it should "be able to get size of a local file" in {
    val p = Files.createTempFile("Y", ".txt")
    try {
      val buf = "hello bunny"
      val docSrc = StringFileNode(buf)
      docSrc.localize(p, overwrite = true)
      val (evalPaths, fileResolver) = setup()
      val ioSupp = IoSupport(evalPaths, fileResolver, logger)
      val len = ioSupp.size(p.toString, placeholderSourceLocation)
      len shouldBe buf.length
      val data = ioSupp.readFile(p.toString, placeholderSourceLocation)
      data shouldBe buf
    } finally {
      Files.delete(p)
    }
  }

  it should "be able to use size from Stdlib" in {
    val p = Files.createTempFile("Y", ".txt")
    val buf = "make Shasta full"
    try {
      val docSrc = StringFileNode(buf)
      docSrc.localize(p, overwrite = true)
      val (evalPaths, fileResolver) = setup()
      val stdlib = Stdlib(evalPaths, WdlVersion.V1, fileResolver, logger)
      val retval =
        stdlib.call("size", Vector(WdlValues.V_String(p.toString)), placeholderSourceLocation)
      inside(retval) {
        case WdlValues.V_Float(x) =>
          x.toInt shouldBe buf.length
      }
    } finally {
      Files.delete(p)
    }
  }

  it should "evaluate globs" in {
    val (evalPaths, fileResolver) = setup()
    val workDir = evalPaths.getWorkDir(ensureExists = true)
    val file1 = workDir.resolve("file1.txt")
    val file2 = workDir.resolve("file2.txt")
    val files = Set(file1, file2)
    files.foreach { path =>
      FileUtils.writeFileContent(path, "foo")
    }
    val ioSupp = IoSupport(evalPaths, fileResolver, logger)
    val globFiles = ioSupp.glob("./*.txt")
    globFiles.toSet shouldBe files.map(_.toString)
  }
}
