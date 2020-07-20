package wdlTools.eval

import java.nio.file.{Files, Path, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.util.{FileAccessProtocol, FileSource, Logger, FileSourceResolver, StringFileSource}

class IoSupportTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval").getPath)
  private val logger = Logger.Normal

  private def safeMkdir(path: Path): Unit = {
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
    override def resolve(uri: String): FileSource = ???
  }

  private lazy val evalPaths: EvalPaths = {
    val baseDir = Files.createTempDirectory("eval")
    val dirs = Vector("home", "tmp").map { subdir =>
      val d = baseDir.resolve(subdir)
      safeMkdir(d)
      d
    }
    EvalPaths(dirs(0), dirs(1))
  }

  private lazy val fileResolver =
    FileSourceResolver.create(Vector(srcDir, evalPaths.getHomeDir()), Vector(DxProtocol))

  private val placeholderSourceLocation = SourceLocation.empty

  it should "be able to get size of a local file" in {
    val p = Files.createTempFile("Y", ".txt")
    try {
      val buf = "hello bunny"
      val docSrc = StringFileSource(buf, Some(p))
      docSrc.localize(overwrite = true)
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
      val docSrc = StringFileSource(buf, Some(p))
      docSrc.localize(overwrite = true)
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
}
