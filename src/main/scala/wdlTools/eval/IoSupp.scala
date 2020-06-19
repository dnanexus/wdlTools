package wdlTools.eval

import java.io.{ByteArrayOutputStream, IOException}
import java.net.{HttpURLConnection, URI, URL}
import java.nio.charset.Charset
import java.nio.file._

import wdlTools.syntax.TextSource
import wdlTools.util.{Options, Util, Verbosity}

import scala.jdk.CollectionConverters._
import scala.util.Random

// Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
case class IoSupp(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL]) {

  /** Figure out which scheme should be used
    *
    *  For S3              s3://
    *  For google cloud    gs://
    *  For dnanexus        dx://
    *
    */
  def getProtocol(uri: URI, text: TextSource): FileAccessProtocol = {
    val protocol =
      try {
        uri.getScheme
      } catch {
        case _: NullPointerException =>
          "file"
      }

    if (opts.verbosity >= Verbosity.Verbose) {
      System.out.println(s"uri ${uri} has protocol ${protocol}")
    }

    evalCfg.protocols.get(protocol) match {
      case None =>
        throw new EvalException(s"Protocol ${protocol} not supported", text, docSourceUrl)
      case Some(proto) =>
        proto
    }
  }

  def size(pathOrUri: String, text: TextSource): Long = {
    val uri = Util.getUri(pathOrUri)
    val proto = getProtocol(uri, text)
    try {
      proto.size(uri)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error getting size of ${pathOrUri}, msg=${e.getMessage}",
                                text,
                                docSourceUrl)
    }
  }

  // Create an empty file if the given path does not already exist - used by stdout() and stderr()
  def ensureFileExists(path: Path, name: String, text: TextSource): Unit = {
    val file = path.toFile
    if (!file.exists()) {
      try {
        file.createNewFile()
      } catch {
        case e: IOException =>
          throw new EvalException(
              s"${name} file ${file} does not exist and cannot be created: ${e.getMessage}",
              text,
              docSourceUrl
          )
      }
    }
  }

  // Read the contents of the file/URL and return a string
  def readFile(pathOrUri: String, text: TextSource): String = {
    val uri = Util.getUri(pathOrUri)
    val proto = getProtocol(uri, text)
    try {
      proto.readFile(uri)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error read file ${pathOrUri}, msg=${e.getMessage}",
                                text,
                                docSourceUrl)
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

  def mkTempFile(): Path = {
    val rndName = Random.alphanumeric.take(8).mkString("")
    evalCfg.tmpDir.resolve(rndName)
  }
}

object IoSupp {
  val MiB = BigDecimal(1024 * 1024)
  val MaxFileSizeMiB = BigDecimal(256)

  case class LocalFiles(encoding: Charset) extends FileAccessProtocol {
    val prefixes = Vector("", "file")

    def size(uri: URI): Long = {
      val p = Paths.get(uri.getPath)
      if (!Files.exists(p)) {
        throw new FileSystemNotFoundException(s"File ${p} not found")
      }
      try {
        p.toFile.length()
      } catch {
        case t: Throwable =>
          throw new Exception(s"Error getting size of file ${uri}: ${t.getMessage}")
      }
    }

    def readFile(uri: URI): String = {
      // check that file isn't too big
      val fileSizeMiB = BigDecimal(size(uri)) / MiB
      if (fileSizeMiB > MaxFileSizeMiB) {
        throw new Exception(
            s"${uri} size is ${fileSizeMiB} MiB; reading files larger than ${MaxFileSizeMiB} MiB is unsupported"
        )
      }
      new String(Files.readAllBytes(Paths.get(uri.getPath)), encoding)
    }
  }

  case class HttpProtocol(encoding: Charset) extends FileAccessProtocol {
    val prefixes = Vector("http", "https")

    // A url
    // https://stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java
    //
    def size(uri: URI): Long = {
      val url = uri.toURL
      var conn: HttpURLConnection = null
      try {
        conn = url.openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("HEAD")
        conn.getContentLengthLong
      } catch {
        case t: Throwable =>
          throw new Exception(s"Error getting size of URL ${url}: ${t.getMessage}")
      } finally {
        if (conn != null) {
          conn.disconnect()
        }
      }
    }

    def readFile(uri: URI): String = {
      // check that file isn't too big
      val fileSizeMiB = BigDecimal(size(uri)) / MiB
      if (fileSizeMiB > MaxFileSizeMiB)
        throw new Exception(
            s"${uri} size is ${fileSizeMiB} MiB; reading files larger than ${MaxFileSizeMiB} MiB is unsupported"
        )

      val url = uri.toURL
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

        new String(buffer.toByteArray, encoding)
      } finally {
        is.close()
        buffer.close()
      }
    }
  }
}
