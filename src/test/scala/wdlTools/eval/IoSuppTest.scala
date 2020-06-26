package wdlTools.eval

import java.net.URI
import java.nio.file.{Files, Path, Paths}

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.{TextSource, WdlVersion}
import wdlTools.types.{TypeCheckingRegime, TypeOptions}
import wdlTools.util.{Logger, Util}

class IoSuppTest extends AnyFlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval").getPath)
  private val opts =
    TypeOptions(typeChecking = TypeCheckingRegime.Lenient,
                localDirectories = Vector(srcDir),
                logger = Logger.Normal)

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
    def size(uri: URI): Long = ???
    def readFile(uri: URI): String = ???
  }

  private lazy val evalCfg: EvalConfig = {
    val baseDir = Files.createTempDirectory("eval")
    val dirs = Vector("home", "tmp").map { subdir =>
      val d = baseDir.resolve(subdir)
      safeMkdir(d)
      d
    }
    val stdout = baseDir.resolve("stdout")
    val stderr = baseDir.resolve("stderr")
    EvalConfig.make(dirs(0), dirs(1), stdout, stderr, Vector(DxProtocol))
  }

  private val ioSupp = new IoSupp(opts, evalCfg, None)
  private val dummyTextSource = TextSource(0, 0, 0, 0)

  it should "Figure out protocols" in {
    // this style of uri will have authority='file-FGqFJ8Q0ffPGVz3zGy4FK02P::' and path='//fileB'
    val uri = URI.create("dx://file-FGqFJ8Q0ffPGVz3zGy4FK02P:://fileB")
    val proto = ioSupp.getProtocol(uri, dummyTextSource)
    proto.prefixes shouldBe Vector("dx")
  }

  it should "Recognize http" in {
    // recognize http
    val proto = ioSupp.getProtocol(URI.create("http://A.txt"), dummyTextSource)
    proto.prefixes.iterator sameElements Vector("http", "https")

    val proto2 = ioSupp.getProtocol(URI.create("https://A.txt"), dummyTextSource)
    proto2.prefixes.iterator sameElements Vector("http", "https")
  }

  it should "Recognize local files" in {
    // recognize local file access
    val proto = ioSupp.getProtocol(URI.create("file:///A.txt"), dummyTextSource)
    proto.prefixes.iterator sameElements Vector("", "file")
  }

  it should "be able to get size of a local file" in {
    val p = Files.createTempFile("Y", ".txt")
    try {
      val buf = "hello bunny"
      Util.writeFileContent(p, buf)
      val len = ioSupp.size(p.toString, dummyTextSource)
      len shouldBe buf.length
      val data = ioSupp.readFile(p.toString, dummyTextSource)
      data shouldBe buf
    } finally {
      Files.delete(p)
    }
  }

  it should "be able to use size from Stdlib" in {
    val p = Files.createTempFile("Y", ".txt")
    val buf = "make Shasta full"
    try {
      Util.writeFileContent(p, buf)
      val stdlib = Stdlib(opts, evalCfg, WdlVersion.V1, None)
      val retval = stdlib.call("size", Vector(WdlValues.V_String(p.toString)), dummyTextSource)
      inside(retval) {
        case WdlValues.V_Float(x) =>
          x.toInt shouldBe buf.length
      }
    } finally {
      Files.delete(p)
    }
  }
}
