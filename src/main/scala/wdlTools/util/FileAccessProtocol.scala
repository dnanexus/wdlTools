package wdlTools.util

import java.io.{ByteArrayOutputStream, FileOutputStream, OutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.Charset
import java.nio.file.{FileSystemNotFoundException, Files, Path, Paths}

// A protocol defined by the user. Intended for allowing access to
// private/public clouds such as S3, Azure, or dnanexus. This has to be a web
// protocol, meaning that the prefix has to include "://". Anything
// else is considered a local file.
//
// PREFIX
//   For S3              s3://
//   For google cloud    gs://
//   For dnanexus        dx://
//
trait FileAccessProtocol {
  val prefixes: Vector[String]

  // Get the size of the file in bytes
  def size(uri: URI): Long

  // Download URI to a local path
  // If dest is a directory, use uri to deterimine the filename
  def downloadFile(uri: URI, dest: Path, isDirectory: Boolean): Path

  // Read the entire file into a string
  def readFile(uri: URI): String
}

object FileAccessProtocol {
  class NoSuchProtocolException(name: String) extends Exception(s"Protocol ${name} not supported")

  /** Figure out which scheme should be used
    *
    *  For S3              s3://
    *  For google cloud    gs://
    *  For dnanexus        dx://
    *
    */
  def getProtocol(uri: URI,
                  protocols: Map[String, FileAccessProtocol],
                  logger: Logger): FileAccessProtocol = {
    val protocol =
      try {
        uri.getScheme
      } catch {
        case _: NullPointerException =>
          "file"
      }

    if (logger.isVerbose) {
      System.out.println(s"uri ${uri} has protocol ${protocol}")
    }

    protocols.get(protocol) match {
      case None        => throw new NoSuchProtocolException(protocol)
      case Some(proto) => proto
    }
  }

  val MiB = BigDecimal(1024 * 1024)
  val MaxFileSizeMiB = BigDecimal(256)

  case class LocalFiles(encoding: Charset, logger: Logger) extends FileAccessProtocol {
    val prefixes = Vector("", "file")

    override def size(uri: URI): Long = {
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

    override def readFile(uri: URI): String = {
      // check that file isn't too big
      val fileSizeMiB = BigDecimal(size(uri)) / MiB
      if (fileSizeMiB > MaxFileSizeMiB) {
        throw new Exception(
            s"${uri} size is ${fileSizeMiB} MiB; reading files larger than ${MaxFileSizeMiB} MiB is unsupported"
        )
      }
      new String(Files.readAllBytes(Paths.get(uri.getPath)), encoding)
    }

    override def downloadFile(uri: URI, dest: Path, isDirectory: Boolean): Path = {
      val src = Paths.get(uri.getPath).toRealPath()
      if (src == dest) {
        logger.trace(s"Skipping 'download' of local file ${uri} - source and dest paths are equal")
        dest
      } else {
        val destFile = if (isDirectory) {
          dest.resolve(src.getFileName)
        } else {
          dest
        }
        logger.trace(s"Copying file ${src} to ${destFile}")
        Files.copy(src, destFile)
        destFile
      }
    }
  }

  case class HttpProtocol(encoding: Charset, logger: Logger) extends FileAccessProtocol {
    val prefixes = Vector("http", "https")

    // A url
    // https://stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java
    //
    override def size(uri: URI): Long = {
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

    private def fetchUri(uri: URI, buffer: OutputStream): Unit = {
      val url = uri.toURL
      val is = url.openStream()
      try {
        // read all the bytes from the URL
        var nRead = 0
        val data = new Array[Byte](16384)
        while (nRead > 0) {
          nRead = is.read(data, 0, data.length)
          if (nRead > 0)
            buffer.write(data, 0, nRead)
        }
      } finally {
        is.close()
      }
    }

    override def readFile(uri: URI): String = {
      // check that file isn't too big
      val fileSizeMiB = BigDecimal(size(uri)) / MiB
      if (fileSizeMiB > MaxFileSizeMiB) {
        throw new Exception(
            s"${uri} size is ${fileSizeMiB} MiB; reading files larger than ${MaxFileSizeMiB} MiB is unsupported"
        )
      }
      val buffer = new ByteArrayOutputStream()
      try {
        fetchUri(uri, buffer)
        new String(buffer.toByteArray, encoding)
      } finally {
        buffer.close()
      }
    }

    override def downloadFile(uri: URI, dest: Path, isDirectory: Boolean): Path = {
      val destPath = if (isDirectory) {
        dest.resolve(Util.getFilename(uri.getPath))
      } else {
        dest
      }
      val buffer = new FileOutputStream(destPath.toFile)
      try {
        fetchUri(uri, buffer)
      } finally {
        buffer.close()
      }
      destPath
    }
  }
}
