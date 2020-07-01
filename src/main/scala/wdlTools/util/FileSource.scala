package wdlTools.util

import java.io.{ByteArrayOutputStream, FileNotFoundException, FileOutputStream, OutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.Charset
import java.nio.file.{FileAlreadyExistsException, FileSystemNotFoundException, Files, Path, Paths}

import wdlTools.util.Util.{FILE_SCHEME, getUriScheme}

import scala.io.Source

trait FileSource {
  def localPath: Path

  def fileName: String = localPath.getFileName.toString

  def encoding: Charset

  protected def checkFileSize(): Unit = {
    // check that file isn't too big
    val fileSizeMiB = BigDecimal(size) / FileSource.MiB
    if (fileSizeMiB > FileSource.MaxFileSizeMiB) {
      throw new Exception(
          s"""${toString} size is ${fileSizeMiB} MiB;
             |reading files larger than ${FileSource.MaxFileSizeMiB} MiB is unsupported""".stripMargin
      )
    }
  }

  /**
    * Gets the size of the file in bytes.
    * @return
    */
  def size: Long

  def localizeToDir(dir: Path, overwrite: Boolean = false): Path = {
    localize(dir.resolve(fileName), overwrite)
  }

  /**
    * Localizes file to a local path.
    * @param file destination file
    * @param overwrite - whether to overwrite an existing file
    * @return the actual path to which the file was localized
    */
  def localize(file: Path = localPath, overwrite: Boolean = false): Path

  /**
    * Reads the entire file into a byte array.
    * @return
    */
  def readBytes: Array[Byte]

  /**
    * Reads the entire file into a string.
    * @return
    */
  def readString: String

  /**
    * Reads the entire file into a vector of lines.
    * @return
    */
  def readLines: Vector[String]
}

object FileSource {
  val MiB = BigDecimal(1024 * 1024)
  val MaxFileSizeMiB = BigDecimal(256)

  /**
    * Localize a collection of documents to disk.
    * @param docs the documents to write
    * @param outputDir the output directory; if None, the URI is converted to an absolute path if possible
    * @param overwrite whether it is okay to overwrite an existing file
    */
  def localizeAll(docs: Vector[FileSource],
                  outputDir: Option[Path],
                  overwrite: Boolean = false): Unit = {
    docs.foreach { doc =>
      if (outputDir.isDefined) {
        doc.localizeToDir(outputDir.get, overwrite)
      } else {
        doc.localize(overwrite = overwrite)
      }
    }
  }
}

abstract class AbstractFileSource(val encoding: Charset) extends FileSource {

  /**
    * Gets the size of the file in bytes.
    *
    * @return
    */
  override lazy val size: Long = readBytes.length

  override def localize(file: Path = localPath, overwrite: Boolean = false): Path = {
    if (Files.exists(file) && !overwrite) {
      throw new FileAlreadyExistsException(
          s"file ${localPath} already exists and overwrite = false"
      )
    }
    val absFile = Util.absolutePath(file)
    localizeTo(absFile)
    absFile
  }

  protected def localizeTo(file: Path): Unit
}

class NoSuchProtocolException(name: String) extends Exception(s"Protocol ${name} not supported")

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

  /**
    * Resolve a URI
    * @param uri the file URI
    * @param exists if Some(true), the FileSource must exist; if Some(false), the FileSource must not exist;
    *               if None, no check is done for whether or not the FileSource exists
    * @return
    */
  def resolve(uri: String, exists: Option[Boolean] = None): FileSource
}

abstract class AbstractVirtualFileSource(name: Option[Path] = None,
                                         encoding: Charset = Util.DefaultEncoding)
    extends AbstractFileSource(encoding) {
  override lazy val toString: String = name.map(_.toString).getOrElse("<string>")

  override def localPath: Path = {
    name.getOrElse(
        throw new RuntimeException("virtual FileSource has no localPath")
    )
  }

  override lazy val readBytes: Array[Byte] = readString.getBytes(encoding)

  override protected def localizeTo(file: Path): Unit = {
    Util.writeFileContent(file, readString)
  }
}

case class StringFileSource(string: String,
                            name: Option[Path] = None,
                            override val encoding: Charset = Util.DefaultEncoding)
    extends AbstractVirtualFileSource(name, encoding) {
  override def readString: String = string

  lazy val readLines: Vector[String] = {
    Source.fromString(string).getLines().toVector
  }
}

object StringFileSource {
  def withName(name: String, content: String): StringFileSource = {
    StringFileSource(content, Some(Util.getPath(name)))
  }

  lazy val empty: StringFileSource = StringFileSource("")
}

case class LinesFileSource(override val readLines: Vector[String],
                           name: Option[Path] = None,
                           override val encoding: Charset = Util.DefaultEncoding,
                           lineSeparator: String = "\n",
                           trailingNewline: Boolean = true)
    extends AbstractVirtualFileSource(name, encoding) {
  override def readString: String = {
    val s = readLines.mkString(lineSeparator)
    if (trailingNewline) {
      s + lineSeparator
    } else {
      s
    }
  }
}

object LinesFileSource {
  def withName(name: String, lines: Vector[String]): LinesFileSource = {
    LinesFileSource(lines, Some(Util.getPath(name)))
  }
}

abstract class AbstractPhysicalFileSource(override val encoding: Charset)
    extends AbstractFileSource(encoding) {

  override lazy val readString: String = new String(readBytes, encoding)

  override lazy val readLines: Vector[String] = {
    Source.fromBytes(readBytes, encoding.name).getLines().toVector
  }
}

case class LocalFileSource(override val localPath: Path,
                           logger: Logger,
                           override val encoding: Charset)
    extends AbstractPhysicalFileSource(encoding) {
  override lazy val toString: String = localPath.toString

  override lazy val size: Long = {
    if (!Files.exists(localPath)) {
      throw new FileSystemNotFoundException(s"File ${localPath} not found")
    }
    try {
      localPath.toFile.length()
    } catch {
      case t: Throwable =>
        throw new Exception(s"Error getting size of file ${localPath}: ${t.getMessage}")
    }
  }

  /**
    * Reads the entire file into a byte array.
    *
    * @return
    */
  override def readBytes: Array[Byte] = {
    checkFileSize()
    Files.readAllBytes(localPath)
  }

  override protected def localizeTo(file: Path): Unit = {
    if (localPath == file) {
      logger.trace(
          s"Skipping 'download' of local file ${localPath} - source and dest paths are equal"
      )
    } else {
      logger.trace(s"Copying file ${localPath} to ${file}")
      Files.copy(localPath, file)
    }
  }
}

case class LocalFileAccessProtocol(searchPath: Vector[Path] = Vector.empty,
                                   logger: Logger = Logger.Quiet,
                                   encoding: Charset = Util.DefaultEncoding)
    extends FileAccessProtocol {
  val prefixes = Vector("", Util.FILE_SCHEME)

  // search for a relative path in the directories of `searchPath`
  private def findInPath(relPath: String): Path = {
    searchPath
      .map(d => d.resolve(relPath))
      .collectFirst {
        case fp if Files.exists(fp) => fp.toRealPath()
      }
      .getOrElse(
          throw new FileNotFoundException(
              s"Could not resolve relative path ${relPath} in search path [${searchPath.mkString(",")}]"
          )
      )
  }

  def resolve(uri: String, exists: Option[Boolean] = None): FileSource = {
    val path: Path = getUriScheme(uri) match {
      case Some(FILE_SCHEME) => Paths.get(URI.create(uri))
      case None              => Util.getPath(uri)
      case _                 => throw new Exception(s"${uri} is not a path or file:// URI")
    }
    resolvePath(path, exists)
  }

  def resolvePath(path: Path, exists: Option[Boolean] = None): FileSource = {
    val resolved: Path = if (Files.exists(path)) {
      path.toRealPath()
    } else if (path.isAbsolute) {
      path
    } else if (searchPath.nonEmpty) {
      findInPath(path.toString)
    } else {
      Util.absolutePath(path)
    }
    if (exists.nonEmpty) {
      val existing = Files.exists(resolved)
      if (exists.get && !existing) {
        throw new FileNotFoundException(s"Path does not exist ${resolved}")
      }
      if (!exists.get && existing) {
        throw new FileAlreadyExistsException(s"Path already exists ${resolved}")
      }
    }
    LocalFileSource(resolved, logger, encoding)
  }
}

case class RemoteFileSource(uri: URI, override val encoding: Charset, logger: Logger)
    extends AbstractPhysicalFileSource(encoding) {
  private var hasBytes: Boolean = false

  override def localPath: Path = Paths.get(uri.getPath).getFileName

  override lazy val toString: String = uri.toString

  // https://stackoverflow.com/questions/12800588/how-to-calculate-a-file-size-from-url-in-java
  override lazy val size: Long = {
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

  private def fetchUri(buffer: OutputStream, chunkSize: Int = 16384): Int = {
    val url = uri.toURL
    val is = url.openStream()
    try {
      // read all the bytes from the URL
      var nRead = 0
      var totalRead = 0
      val data = new Array[Byte](chunkSize)
      do {
        nRead = is.read(data, 0, chunkSize)
        if (nRead > 0) {
          buffer.write(data, 0, nRead)
          totalRead += nRead
        }
      } while (nRead > 0)
      totalRead
    } finally {
      is.close()
    }
  }

  override lazy val readBytes: Array[Byte] = {
    checkFileSize()
    val buffer = new ByteArrayOutputStream()
    try {
      fetchUri(buffer)
      hasBytes = true
      buffer.toByteArray
    } finally {
      buffer.close()
    }
  }

  override protected def localizeTo(file: Path): Unit = {
    // avoid re-downloading the file if we've already cached the bytes
    if (hasBytes) {
      Util.writeFileContent(file, new String(readBytes, encoding))
    } else {
      val buffer = new FileOutputStream(file.toFile)
      try {
        fetchUri(buffer)
      } finally {
        buffer.close()
      }
    }
  }
}

case class HttpFileAccessProtocol(logger: Logger = Logger.Quiet,
                                  encoding: Charset = Util.DefaultEncoding)
    extends FileAccessProtocol {
  val prefixes = Vector(Util.HTTP_SCHEME, Util.HTTPS_SCHEME)

  override def resolve(uri: String, exists: Option[Boolean] = None): FileSource = {
    val src = RemoteFileSource(URI.create(uri), encoding, logger)
    // if the resource is required to exist check that it's accessible
    if (exists.forall(b => b)) {
      src.size
    }
    src
  }
}

case class FileSourceResolver(protocols: Vector[FileAccessProtocol]) {
  private lazy val protocolMap: Map[String, FileAccessProtocol] =
    protocols.flatMap(prot => prot.prefixes.map(prefix => prefix -> prot)).toMap

  def getProtocol(uriOrPath: String): FileAccessProtocol = {
    val scheme = Util.getUriScheme(uriOrPath).getOrElse(Util.FILE_SCHEME)
    protocolMap.get(scheme) match {
      case None        => throw new NoSuchProtocolException(scheme)
      case Some(proto) => proto
    }
  }

  def resolve(uriOrPath: String): FileSource = {
    getProtocol(uriOrPath).resolve(uriOrPath)
  }

  def fromPath(path: Path): FileSource = {
    protocolMap.get(Util.FILE_SCHEME) match {
      case Some(proto: LocalFileAccessProtocol) => proto.resolvePath(path)
      case _                                    => throw new RuntimeException("No file protocol")
    }
  }
}

object FileSourceResolver {
  def create(localDirectories: Vector[Path] = Vector.empty,
             userProtocols: Vector[FileAccessProtocol] = Vector.empty,
             logger: Logger = Logger.Quiet,
             encoding: Charset = Util.DefaultEncoding): FileSourceResolver = {
    val protocols: Vector[FileAccessProtocol] = Vector(
        LocalFileAccessProtocol(localDirectories, logger, encoding),
        HttpFileAccessProtocol(logger, encoding)
    )
    FileSourceResolver(protocols ++ userProtocols)
  }
}