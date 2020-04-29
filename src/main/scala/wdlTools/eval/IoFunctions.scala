package wdlTools.eval

import java.io.ByteArrayOutputStream
import java.net.{URL, HttpURLConnection}
import java.nio.charset.StandardCharsets._
import java.nio.file.{Files, FileSystems, Path, Paths, PathMatcher}
import scala.collection.JavaConverters._

import wdlTools.eval.WdlValues._
import wdlTools.util.{EvalConfig, Options, Util}
import wdlTools.util.Verbosity._

case class IoFunctions(opts: Options, evalCfg: EvalConfig) {

  private def isURL(pathOrUrl: String): Boolean = {
    pathOrUrl.contains("://")
  }

  // Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
  private def readLocalFile(p: Path): WV_String = {
    if (!Files.exists(p))
      throw new RuntimeException(s"File ${p} not found")

    // TODO: Check that the file isn't over 256 MiB.
    // I have seen a user read a 30GiB file this case, and cause an ugly error.

    val content = new String(Files.readAllBytes(p), UTF_8)
    WV_String(content)
  }

  private def readURLContent(url: URL): WV_String = {
    val is = url.openStream()
    val buffer: ByteArrayOutputStream = new ByteArrayOutputStream()

    try {
      // read all the bytes from the URL
      var nRead = 0
      val data = new Array[Byte](16384)

      while (nRead > 0) {
        nRead = is.read(data, 0, data.length)
        if (nRead > 0)
          buffer.write(data, 0, nRead)
      }

      // make it a string
      val strVal = new String(buffer.toByteArray(), UTF_8)
      WV_String(strVal)
    } catch {
      case e: Throwable =>
        Util.error(s"Error reading ${url.toString}")
        throw e
    } finally {
      is.close()
      buffer.close()
    }
  }

  // Read the contents of the file/URL and return a string
  //
  // This may be a binary file that does not lend itself to splitting into lines.
  // Hence, we aren't using the Source module.
  def readFile(pathOrUrl: String): WV_String = {
    if (isURL(pathOrUrl)) {
      // A URL
      val url = new URL(pathOrUrl)
      val content = url.getProtocol match {
        case "http" | "https" => readURLContent(new URL(pathOrUrl))
        case "file"           => readLocalFile(Paths.get(pathOrUrl))
        case _                => throw new RuntimeException(s"unknown protocol in URL ${url}")
      }
      return content
    }

    // This is a local file
    val p = Paths.get(pathOrUrl)
    readLocalFile(p)
  }

  /**
    * Write "content" to the specified "path" location
    */
  def writeFile(pathOrUrl: String, content: String): WV_File = {
    if (isURL(pathOrUrl)) {
      throw new RuntimeException(
          s"writeFile: implemented only for local files (${pathOrUrl})"
      )
    }

    val path = Paths.get(pathOrUrl)
    Files.write(path, content.getBytes())
    WV_File(path.toString)
  }

  /**
    * Glob files and directories using the provided pattern.
    * @return the list of globbed paths
    */
  def glob(pattern: String): Vector[String] = {
    if (opts.verbosity == Verbose) {
      System.out.println(s"glob(${pattern})")
    }
    val baseDir = evalCfg.homeDir
    val matcher: PathMatcher = FileSystems
      .getDefault()
      .getPathMatcher(s"glob:${baseDir.toString}/${pattern}")
    val retval =
      if (!Files.exists(baseDir)) {
        Vector.empty[String]
      } else {
        val files = Files
          .walk(baseDir)
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .filter(matcher.matches(_))
          .map(_.toString)
          .toVector
        files.sorted
      }
    if (opts.verbosity == Verbose) {
      System.out.println(s"""glob results=${retval.mkString("\n")}""")
    }
    retval
  }

  /**
    * Return the size of the file located at "path"
    */
  def size(pathOrUrl: String): Long = {
    if (isURL(pathOrUrl)) {
      // A url
      // https://stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java
      val url = new URL(pathOrUrl)
      var conn: HttpURLConnection = null
      try {
        conn = url.openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("HEAD")
        return conn.getContentLengthLong()
      } catch {
        case e: Throwable =>
          throw e
      } finally {
        if (conn != null) {
          conn.disconnect()
        }
      }
    }

    // a file
    val p = Paths.get(pathOrUrl)
    return p.toFile.length()
  }
}
