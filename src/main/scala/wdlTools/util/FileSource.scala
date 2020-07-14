package wdlTools.util

import java.io.{ByteArrayOutputStream, FileNotFoundException, FileOutputStream, OutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.Charset
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}

import wdlTools.util.FileUtils.{FILE_SCHEME, getUriScheme}

import scala.io.Source

trait FileSource {
  def localPath: Path

  def fileName: String = localPath.getFileName.toString

  def encoding: Charset

  def isDirectory: Boolean = false

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
    val absFile = FileUtils.absolutePath(file)
    localizeTo(absFile)
    absFile
  }

  protected def localizeTo(file: Path): Unit
}

trait RealFileSource extends FileSource {

  /**
    * The original value that was resolved to get this FileSource.
    */
  def value: String

  override def toString: String = value
}

abstract class AbstractRealFileSource(override val value: String, override val encoding: Charset)
    extends AbstractFileSource(encoding)
    with RealFileSource {

  override lazy val readString: String = new String(readBytes, encoding)

  override lazy val readLines: Vector[String] = {
    Source.fromBytes(readBytes, encoding.name).getLines().toVector
  }
}

class NoSuchProtocolException(name: String) extends Exception(s"Protocol ${name} not supported")

class ProtocolFeatureNotSupportedException(name: String, feature: String)
    extends Exception(s"Protocol ${name} does not support feature ${feature}")

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

  val supportsDirectories = false

  /**
    * Resolve a URI
    * @param uri the file URI
    * @return FileSource
    */
  def resolve(uri: String): FileSource

  /**
    * Resolve a URI that points to a directory. Must only be implemented if `supportsDirectories` is true.
    * @param uri the directory URI
    * @return FileSource
    */
  def resolveDirectory(uri: String): FileSource = ???
}

/**
  * A FileSource for a local file.
  * @param value the original path/URI used to resolve this file.
  * @param valuePath the original, non-cannonicalized Path determined from `value` - may be relative
  * @param localPath the absolute, cannonical path to this file
  * @param logger the logger
  * @param encoding the file encoding
  * @param isDirectory whether this FileSource represents a directory
  */
case class LocalFileSource(override val value: String,
                           valuePath: Path,
                           override val localPath: Path,
                           logger: Logger,
                           override val encoding: Charset,
                           override val isDirectory: Boolean = false)
    extends AbstractRealFileSource(value, encoding) {
  def checkExists(exists: Boolean): Unit = {
    val existing = Files.exists(localPath)
    if (exists && !existing) {
      throw new FileNotFoundException(s"Path does not exist ${localPath}")
    }
    if (!exists && existing) {
      throw new FileAlreadyExistsException(s"Path already exists ${localPath}")
    }
  }

  override lazy val size: Long = {
    checkExists(true)
    if (isDirectory && Files.isDirectory(localPath)) {
      throw new Exception("Cannot get the size of a directory")
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

  // TODO: assess whether it is okay to link instead of copy
  override protected def localizeTo(file: Path): Unit = {
    if (localPath == file) {
      logger.trace(
          s"Skipping 'download' of local file ${localPath} - source and dest paths are equal"
      )
    } else if (isDirectory) {
      if (Files.isDirectory(localPath)) {
        logger.trace(s"Copying directory ${localPath} to ${file}")
        FileUtils.copyDirectory(localPath, file)
      } else {
        logger.trace(s"Unpacking archive ${localPath} to ${file}")
        FileUtils.unpackArchive(localPath, file, logger = logger)
      }
    } else {
      logger.trace(s"Copying file ${localPath} to ${file}")
      checkExists(true)
      Files.copy(localPath, file)
    }
  }
}

case class LocalFileAccessProtocol(searchPath: Vector[Path] = Vector.empty,
                                   logger: Logger = Logger.Quiet,
                                   encoding: Charset = FileUtils.DefaultEncoding)
    extends FileAccessProtocol {
  val prefixes = Vector("", FileUtils.FILE_SCHEME)
  override val supportsDirectories: Boolean = true

  private def uriToPath(uri: String): Path = {
    getUriScheme(uri) match {
      case Some(FILE_SCHEME) => Paths.get(URI.create(uri))
      case None              => FileUtils.getPath(uri)
      case _                 => throw new Exception(s"${uri} is not a path or file:// URI")
    }
  }

  def resolve(uri: String): LocalFileSource = {
    resolvePath(uriToPath(uri), Some(uri))
  }

  override def resolveDirectory(uri: String): FileSource = {
    resolvePath(uriToPath(uri), Some(uri), isDirectory = true)
  }

  // search for a relative path in the directories of `searchPath`
  private def findInPath(relPath: String): Option[Path] = {
    searchPath
      .map(d => d.resolve(relPath))
      .collectFirst {
        case fp if Files.exists(fp) => fp.toRealPath()
      }
  }

  def resolvePath(path: Path,
                  value: Option[String] = None,
                  isDirectory: Boolean = false): LocalFileSource = {
    val resolved: Path = if (Files.exists(path)) {
      path.toRealPath()
    } else if (path.isAbsolute) {
      path
    } else {
      findInPath(path.toString).getOrElse(
          // it's a non-existant relative path - localize it to current working dir
          FileUtils.absolutePath(path)
      )
    }
    LocalFileSource(value.getOrElse(path.toString), path, resolved, logger, encoding, isDirectory)
  }
}

case class RemoteFileSource(override val value: String,
                            uri: URI,
                            override val encoding: Charset,
                            logger: Logger,
                            override val isDirectory: Boolean = false)
    extends AbstractRealFileSource(value, encoding) {
  private var hasBytes: Boolean = false

  override def localPath: Path = Paths.get(uri.getPath).getFileName

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

  private def localizeToFile(path: Path): Unit = {
    // avoid re-downloading the file if we've already cached the bytes
    if (hasBytes) {
      FileUtils.writeFileContent(path, new String(readBytes, encoding))
    } else {
      val buffer = new FileOutputStream(path.toFile)
      try {
        fetchUri(buffer)
      } finally {
        buffer.close()
      }
    }
  }

  override protected def localizeTo(file: Path): Unit = {
    if (isDirectory) {
      // localize to a temp file if this is a "directory" (i.e. an archive we're going to unpack)
      val dest = Files.createTempFile("temp", fileName)
      try {
        localizeToFile(dest)
        // Unpack the archive and delete the temp file
        if (Files.exists(file)) {
          FileUtils.deleteRecursive(file)
        }
        FileUtils.unpackArchive(dest, file)
      } finally {
        FileUtils.deleteRecursive(dest)
      }
    } else {
      localizeToFile(file)
    }
  }
}

case class HttpFileAccessProtocol(logger: Logger = Logger.Quiet,
                                  encoding: Charset = FileUtils.DefaultEncoding)
    extends FileAccessProtocol {
  val prefixes = Vector(FileUtils.HTTP_SCHEME, FileUtils.HTTPS_SCHEME)
  // directories are supported via unpacking of archive files
  override val supportsDirectories: Boolean = true

  override def resolve(uri: String): RemoteFileSource = {
    resolve(URI.create(uri), Some(uri))
  }

  def resolve(uri: URI, value: Option[String] = None): RemoteFileSource = {
    RemoteFileSource(value.getOrElse(uri.toString), uri, encoding, logger)
  }

  override def resolveDirectory(uri: String): FileSource = {
    RemoteFileSource(uri, URI.create(uri), encoding, logger, isDirectory = true)
  }
}

case class FileSourceResolver(protocols: Vector[FileAccessProtocol]) {
  private lazy val protocolMap: Map[String, FileAccessProtocol] =
    protocols.flatMap(prot => prot.prefixes.map(prefix => prefix -> prot)).toMap

  private[util] def getProtocolForScheme(scheme: String): FileAccessProtocol = {
    protocolMap.get(scheme) match {
      case None        => throw new NoSuchProtocolException(scheme)
      case Some(proto) => proto
    }
  }

  private[util] def getScheme(uriOrPath: String): String = {
    FileUtils.getUriScheme(uriOrPath).getOrElse(FileUtils.FILE_SCHEME)
  }

  def resolve(uriOrPath: String): FileSource = {
    getProtocolForScheme(getScheme(uriOrPath)).resolve(uriOrPath)
  }

  def resolveDirectory(uriOrPath: String): FileSource = {
    val scheme = getScheme(uriOrPath)
    val proto = getProtocolForScheme(scheme)
    if (!proto.supportsDirectories) {
      throw new ProtocolFeatureNotSupportedException(scheme, "directories")
    }
    proto.resolveDirectory(uriOrPath)
  }

  def fromPath(path: Path): LocalFileSource = {
    getProtocolForScheme(FileUtils.FILE_SCHEME) match {
      case proto: LocalFileAccessProtocol =>
        proto.resolvePath(path)
      case other =>
        throw new RuntimeException(s"Expected LocalFileAccessProtocol not ${other}")
    }
  }
}

object FileSourceResolver {
  def create(localDirectories: Vector[Path] = Vector.empty,
             userProtocols: Vector[FileAccessProtocol] = Vector.empty,
             logger: Logger = Logger.Quiet,
             encoding: Charset = FileUtils.DefaultEncoding): FileSourceResolver = {
    val protocols: Vector[FileAccessProtocol] = Vector(
        LocalFileAccessProtocol(localDirectories, logger, encoding),
        HttpFileAccessProtocol(logger, encoding)
    )
    FileSourceResolver(protocols ++ userProtocols)
  }
}

// A VirtualFileSource only exists in memory - it doesn't have an associated URI and so cannot be resolved

abstract class AbstractVirtualFileSource(name: Option[Path] = None,
                                         encoding: Charset = FileUtils.DefaultEncoding)
    extends AbstractFileSource(encoding) {
  override lazy val toString: String = name.map(_.toString).getOrElse("<string>")

  override def localPath: Path = {
    name.getOrElse(
        throw new RuntimeException("virtual FileSource has no localPath")
    )
  }

  override lazy val readBytes: Array[Byte] = readString.getBytes(encoding)

  override protected def localizeTo(file: Path): Unit = {
    FileUtils.writeFileContent(file, readString)
  }
}

case class StringFileSource(string: String,
                            name: Option[Path] = None,
                            override val encoding: Charset = FileUtils.DefaultEncoding)
    extends AbstractVirtualFileSource(name, encoding) {
  override def readString: String = string

  lazy val readLines: Vector[String] = {
    Source.fromString(string).getLines().toVector
  }
}

object StringFileSource {
  def withName(name: String, content: String): StringFileSource = {
    StringFileSource(content, Some(FileUtils.getPath(name)))
  }

  lazy val empty: StringFileSource = StringFileSource("")
}

case class LinesFileSource(override val readLines: Vector[String],
                           name: Option[Path] = None,
                           override val encoding: Charset = FileUtils.DefaultEncoding,
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
    LinesFileSource(lines, Some(FileUtils.getPath(name)))
  }
}
