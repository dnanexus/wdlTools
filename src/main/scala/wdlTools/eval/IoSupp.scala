package wdlTools.eval

import java.io.ByteArrayOutputStream
import java.net.{HttpURLConnection, MalformedURLException, URL}
import java.nio.file._

import wdlTools.syntax.TextSource
import wdlTools.util.{Options, Util, Verbosity}

import scala.jdk.CollectionConverters._
import scala.util.Random

case class IoSupp(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL]) {
  val MiB = BigDecimal(1024 * 1024)
  val MaxFileSizeMiB = BigDecimal(256)

  private def asPathOrUrl(pathOrUrl: String, text: TextSource): Either[Path, URL] = {
    try {
      if (pathOrUrl.contains("://")) {
        Right(new URL(pathOrUrl))
      } else {
        Left(Paths.get(pathOrUrl))
      }
    } catch {
      case _: MalformedURLException =>
        throw new EvalException(s"Invalid URL ${pathOrUrl}", text, docSourceUrl)
      case _: InvalidPathException =>
        throw new EvalException(s"Invalid path ${pathOrUrl}", text, docSourceUrl)
      case t: Throwable =>
        throw new EvalException(s"Invalid path or URL ${pathOrUrl}: ${t.getMessage}",
                                text,
                                docSourceUrl)
    }
  }

  private def assertExists(p: Path): Unit = {
    if (!Files.exists(p)) {
      throw new FileSystemNotFoundException(s"File ${p} not found")
    }
  }

  // Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
  private def readLocalFile(p: Path): String = {
    assertExists(p)
    new String(Files.readAllBytes(p), evalCfg.encoding)
  }

  private def readUrlContent(url: URL): String = {
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

      new String(buffer.toByteArray, evalCfg.encoding)
    } finally {
      is.close()
      buffer.close()
    }
  }

  // Read the contents of the file/URL and return a string
  def readFile(pathOrUrl: String, text: TextSource): String = {
    // check that file isn't too big
    val fileSizeMiB = BigDecimal(size(pathOrUrl, text)) / MiB
    if (fileSizeMiB > MaxFileSizeMiB) {
      throw new EvalException(
          s"${pathOrUrl} size is ${fileSizeMiB} MiB; reading files larger than ${MaxFileSizeMiB} MiB is unsupported",
          text,
          docSourceUrl
      )
    }
    try {
      asPathOrUrl(pathOrUrl, text) match {
        case Left(path) => readLocalFile(path)
        case Right(url) =>
          url.getProtocol match {
            case "http" | "https" => readUrlContent(new URL(pathOrUrl))
            case "file"           => readLocalFile(Paths.get(pathOrUrl))
            case _                => throw new EvalException(s"unknown protocol in URL ${url}", text, docSourceUrl)
          }
      }
    } catch {
      case e: EvalException =>
        Util.error(s"Error reading ${pathOrUrl}")
        throw e
      case t: Throwable =>
        Util.error(s"Error reading ${pathOrUrl}")
        throw new EvalException(s"Error reading ${pathOrUrl}: ${t.getMessage}", text, docSourceUrl)
    }
  }

  /**
    * Write "content" to the specified "path" location
    */
  def writeFile(p: Path, content: String, text: TextSource): Unit = {
    try {
      Files.write(p, content.getBytes(evalCfg.encoding))
    } catch {
      case t: Throwable =>
        throw new EvalException(s"Error wrting content to file ${p}: ${t.getMessage}",
                                text,
                                docSourceUrl)
    }
  }

  /**
    * Glob files and directories using the provided pattern.
    * @return the list of globbed paths
    */
  def glob(pattern: String): Vector[String] = {
    if (opts.verbosity == Verbosity.Verbose) {
      System.out.println(s"glob(${pattern})")
    }
    val baseDir = evalCfg.homeDir
    val matcher: PathMatcher = FileSystems.getDefault
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
          .filter(matcher.matches)
          .map(_.toString)
          .toVector
        files.sorted
      }
    if (opts.verbosity == Verbosity.Verbose) {
      System.out.println(s"""glob results=${retval.mkString("\n")}""")
    }
    retval
  }

  /**
    * Return the size of the file located at "path"
    */
  def size(pathOrUrl: String, text: TextSource): Long = {
    asPathOrUrl(pathOrUrl, text) match {
      case Left(path) =>
        try {
          assertExists(path)
          path.toFile.length()
        } catch {
          case t: Throwable =>
            throw new EvalException(s"Error getting size of file ${pathOrUrl}: ${t.getMessage}",
                                    text,
                                    docSourceUrl)
        }
      case Right(url) =>
        // A url
        // https://stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java
        var conn: HttpURLConnection = null
        try {
          conn = url.openConnection().asInstanceOf[HttpURLConnection]
          conn.setRequestMethod("HEAD")
          conn.getContentLengthLong
        } catch {
          case t: Throwable =>
            throw new EvalException(s"Error getting size of URL ${url}: ${t.getMessage}",
                                    text,
                                    docSourceUrl)
        } finally {
          if (conn != null) {
            conn.disconnect()
          }
        }
    }
  }

  def mkTempFile(): Path = {
    val rndName = Random.alphanumeric.take(8).mkString("")
    evalCfg.tmpDir.resolve(rndName)
  }
}
