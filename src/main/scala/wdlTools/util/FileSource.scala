package wdlTools.util

import java.io.{ByteArrayOutputStream, FileNotFoundException, FileOutputStream, OutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.Charset
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}

import wdlTools.util.FileUtils.{FileScheme, getUriScheme}

import scala.io.Source
import scala.reflect.ClassTag

/**
  * A FileSource is just that - a source of files. It may represent a single file or
  * a directory of files (such as a local directory or an archive from which files
  * can be extracted). It may be be physically located on local disk, remotely, or
  * in memory.
  */
trait FileSource {

  /**
    * The name of this FileSource.
    */
  def name: String

  /**
    * Whether this FileSource represents a directory.
    */
  def isDirectory: Boolean

  protected def localizeTo(file: Path): Unit

  /**
    * Localizes this FileSource to the specified path.
    * @param path destination path
    * @param overwrite whether to overwrite any existing file/directory
    * @return the absolute destination path
    */
  def localize(path: Path, overwrite: Boolean = false): Path = {
    if (Files.exists(path) && !overwrite) {
      throw new FileAlreadyExistsException(
          s"file ${path} already exists and overwrite = false"
      )
    }
    val absFile = FileUtils.absolutePath(path)
    localizeTo(absFile)
    absFile
  }

  /**
    * Localizes this FileSource to the specified parent directory.
    * @param dir the destination parent directory
    * @param overwrite whether to overwrite any existing file/directory
    * @return the absolute destination path
    */
  def localizeToDir(dir: Path, overwrite: Boolean = false): Path = {
    localize(dir.resolve(name), overwrite)
  }
}

/**
  * A FileSource that has an address, such as a local file path or a URI.
  */
trait AddressableFileSource extends FileSource {

  /**
    * The original value that was resolved to get this FileSource.
    */
  def address: String

  /**
    * The parent of this FileSource.
    */
  def folder: String

  override def toString: String = address
}

/**
  * A FileNode is a FileSource that represents a single phyiscal file.
  * It has a size, and its contents may be read as bytes or a string.
  * A FileNode may be a "directory" - such as an archive file that,
  * when localized, is extracted to a hierarchy of files.
  */
trait FileNode extends FileSource {
  override def isDirectory: Boolean = false

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

  /**
    * The size of the file in bytes.
    */
  def size: Long = readBytes.length

  protected def checkFileSize(): Unit = {
    // check that file isn't too big
    val fileSizeMiB = BigDecimal(size) / FileNode.MiB
    if (fileSizeMiB > FileNode.MaxFileSizeMiB) {
      throw new Exception(
          s"""${toString} size is ${fileSizeMiB} MiB;
             |reading files larger than ${FileNode.MaxFileSizeMiB} MiB is unsupported""".stripMargin
      )
    }
  }
}

trait AddressableFileNode extends FileNode with AddressableFileSource

object FileNode {
  val MiB = BigDecimal(1024 * 1024)
  val MaxFileSizeMiB = BigDecimal(256)
}

abstract class AbstractAddressableFileNode(override val address: String, val encoding: Charset)
    extends AddressableFileNode {

  override def readString: String = {
    new String(readBytes, encoding)
  }

  override def readLines: Vector[String] = {
    Source.fromBytes(readBytes, encoding.name).getLines().toVector
  }
}

case class NoSuchProtocolException(name: String)
    extends Exception(s"Protocol ${name} not supported")

case class ProtocolFeatureNotSupportedException(name: String, feature: String)
    extends Exception(s"Protocol ${name} does not support feature ${feature}")

/**
  * A protocol for resolving FileSources.
  */
trait FileAccessProtocol {

  /**
    * URI schemes that this protocol is able to resolve.
    */
  def schemes: Vector[String]

  /**
    * Whether this protocol supports resolving directories.
    */
  def supportsDirectories = false

  /**
    * Resolves a URI to a FileNode.
    * @param address the file URI
    * @return FileNode
    */
  def resolve(address: String): AddressableFileNode

  /**
    * Resolves a URI that points to a directory. Must only be implemented if `supportsDirectories` is true.
    * @param address the directory URI
    * @return FileSource
    */
  def resolveDirectory(address: String): AddressableFileSource = {
    throw new UnsupportedOperationException
  }

  /**
    * Perform any cleanup/shutdown activities. Called immediately before the program exits.
    */
  def onExit(): Unit = {}
}

/**
  * A FileSource for a local file.
  * @param address the original path/URI used to resolve this file.
  * @param originalPath the original, non-cannonicalized Path determined from `address` - may be relative
  * @param canonicalPath the absolute, cannonical path to this file
  * @param logger the logger
  * @param encoding the file encoding
  * @param isDirectory whether this FileSource represents a directory
  */
case class LocalFileSource(override val address: String,
                           originalPath: Path,
                           canonicalPath: Path,
                           logger: Logger,
                           override val encoding: Charset,
                           override val isDirectory: Boolean = false)
    extends AbstractAddressableFileNode(address, encoding) {

  override lazy val name: String = canonicalPath.getFileName.toString
  override lazy val folder: String = canonicalPath.getParent.toString

  def checkExists(exists: Boolean): Unit = {
    val existing = Files.exists(canonicalPath)
    if (exists && !existing) {
      throw new FileNotFoundException(s"Path does not exist ${canonicalPath}")
    }
    if (!exists && existing) {
      throw new FileAlreadyExistsException(s"Path already exists ${canonicalPath}")
    }
  }

  override lazy val size: Long = {
    checkExists(true)
    if (isDirectory && Files.isDirectory(canonicalPath)) {
      throw new Exception("Cannot get the size of a directory")
    }
    try {
      canonicalPath.toFile.length()
    } catch {
      case t: Throwable =>
        throw new Exception(s"Error getting size of file ${canonicalPath}: ${t.getMessage}")
    }
  }

  override def readBytes: Array[Byte] = {
    checkFileSize()
    FileUtils.readFileBytes(canonicalPath)
  }

  // TODO: assess whether it is okay to link instead of copy
  override protected def localizeTo(file: Path): Unit = {
    if (canonicalPath == file) {
      logger.trace(
          s"Skipping 'download' of local file ${canonicalPath} - source and dest paths are equal"
      )
    } else if (isDirectory) {
      if (Files.isDirectory(canonicalPath)) {
        logger.trace(s"Copying directory ${canonicalPath} to ${file}")
        FileUtils.copyDirectory(canonicalPath, file)
      } else {
        logger.trace(s"Unpacking archive ${canonicalPath} to ${file}")
        FileUtils.unpackArchive(canonicalPath, file, logger = logger)
      }
    } else {
      logger.trace(s"Copying file ${canonicalPath} to ${file}")
      checkExists(true)
      Files.copy(canonicalPath, file)
    }
  }

  // two LocalFileSources may differ in `value`s but have the same `localPath`
  override def equals(obj: Any): Boolean = {
    obj match {
      case that: LocalFileSource => this.canonicalPath == that.canonicalPath
      case _                     => false
    }
  }

  override def toString: String = canonicalPath.toString
}

case class LocalFileAccessProtocol(searchPath: Vector[Path] = Vector.empty,
                                   logger: Logger = Logger.Quiet,
                                   encoding: Charset = FileUtils.DefaultEncoding)
    extends FileAccessProtocol {
  override val schemes = Vector("", FileUtils.FileScheme)
  override val supportsDirectories: Boolean = true

  private def addressToPath(address: String): Path = {
    getUriScheme(address) match {
      case Some(FileScheme) => Paths.get(URI.create(address))
      case None             => FileUtils.getPath(address)
      case _                => throw new Exception(s"${address} is not a path or file:// URI")
    }
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

  def resolve(address: String): LocalFileSource = {
    resolvePath(addressToPath(address), Some(address))
  }

  override def resolveDirectory(address: String): LocalFileSource = {
    resolvePath(addressToPath(address), Some(address), isDirectory = true)
  }
}

case class HttpFileSource(override val address: String,
                          uri: URI,
                          override val encoding: Charset,
                          logger: Logger,
                          override val isDirectory: Boolean = false)
    extends AbstractAddressableFileNode(address, encoding) {

  private lazy val path = Paths.get(uri.getPath)
  override lazy val name: String = path.getFileName.toString
  override lazy val folder: String = path.getParent.toString
  private var hasBytes: Boolean = false

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
      val dest = Files.createTempFile("temp", name)
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
  override val schemes = Vector(FileUtils.HttpScheme, FileUtils.HttpsScheme)
  // directories are supported via unpacking of archive files
  override val supportsDirectories: Boolean = true

  def resolve(uri: URI, value: Option[String] = None): HttpFileSource = {
    HttpFileSource(value.getOrElse(uri.toString), uri, encoding, logger)
  }

  override def resolve(address: String): HttpFileSource = {
    resolve(URI.create(address), Some(address))
  }

  override def resolveDirectory(address: String): HttpFileSource = {
    HttpFileSource(address, URI.create(address), encoding, logger, isDirectory = true)
  }
}

case class FileSourceResolver(protocols: Vector[FileAccessProtocol]) {
  sys.addShutdownHook({
    protocols.foreach { protocol =>
      try {
        protocol.onExit()
      } catch {
        case ex: Throwable =>
          Logger.error(s"Error shutting down protocol ${protocol}", Some(ex))
      }
    }
  })

  private lazy val protocolMap: Map[String, FileAccessProtocol] =
    protocols.flatMap(prot => prot.schemes.map(prefix => prefix -> prot)).toMap

  private[util] def getProtocolForScheme(scheme: String): FileAccessProtocol = {
    protocolMap.get(scheme) match {
      case None        => throw NoSuchProtocolException(scheme)
      case Some(proto) => proto
    }
  }

  private[util] def getScheme(address: String): String = {
    FileUtils.getUriScheme(address).getOrElse(FileUtils.FileScheme)
  }

  def resolve(address: String): AddressableFileNode = {
    getProtocolForScheme(getScheme(address)).resolve(address)
  }

  def resolveDirectory(address: String): AddressableFileSource = {
    val scheme = getScheme(address)
    val proto = getProtocolForScheme(scheme)
    if (!proto.supportsDirectories) {
      throw ProtocolFeatureNotSupportedException(scheme, "directories")
    }
    proto.resolveDirectory(address)
  }

  def fromPath(path: Path): LocalFileSource = {
    getProtocolForScheme(FileUtils.FileScheme) match {
      case proto: LocalFileAccessProtocol =>
        proto.resolvePath(path)
      case other =>
        throw new RuntimeException(s"Expected LocalFileAccessProtocol not ${other}")
    }
  }

  def addToLocalSearchPath(paths: Vector[Path], append: Boolean = true): FileSourceResolver = {
    val newProtos = protocols.map {
      case LocalFileAccessProtocol(searchPath, logger, encoding) =>
        val newSearchPath = if (append) searchPath ++ paths else paths ++ searchPath
        LocalFileAccessProtocol(newSearchPath, logger, encoding)
      case other => other
    }
    FileSourceResolver(newProtos)
  }

  def replaceProtocol[T <: FileAccessProtocol](
      newProtocol: T
  )(implicit tag: ClassTag[T]): FileSourceResolver = {
    val newProtos = protocols.map {
      case _: T  => newProtocol
      case other => other
    }
    FileSourceResolver(newProtos)
  }
}

object FileSourceResolver {
  private var instance: Option[FileSourceResolver] = None

  def get: FileSourceResolver = {
    instance.getOrElse({
      val instance = create()
      set(instance)
      instance
    })
  }

  def set(fileResolver: FileSourceResolver): Option[FileSourceResolver] = {
    val currentInstance = instance
    instance = Some(fileResolver)
    currentInstance
  }

  def set(localDirectories: Vector[Path] = Vector.empty,
          userProtocols: Vector[FileAccessProtocol] = Vector.empty,
          logger: Logger = Logger.get,
          encoding: Charset = FileUtils.DefaultEncoding): Option[FileSourceResolver] = {
    set(create(localDirectories, userProtocols, logger, encoding))
  }

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

/**
  * A VirtualFileNode only exists in memory. It cannot be resolved.
  * @param name node name
  * @param encoding character encoding
  */
abstract class AbstractVirtualFileNode(override val name: String,
                                       val encoding: Charset = FileUtils.DefaultEncoding)
    extends FileNode {

  override lazy val toString: String = name

  override lazy val readBytes: Array[Byte] = readString.getBytes(encoding)

  override protected def localizeTo(file: Path): Unit = {
    FileUtils.writeFileContent(file, readString)
  }
}

case class StringFileNode(contents: String,
                          override val name: String = "<string>",
                          override val encoding: Charset = FileUtils.DefaultEncoding)
    extends AbstractVirtualFileNode(name, encoding) {
  override def readString: String = contents

  lazy val readLines: Vector[String] = {
    Source.fromString(contents).getLines().toVector
  }
}

object StringFileNode {
  def withName(name: String, contents: String): StringFileNode = {
    StringFileNode(contents, name)
  }

  lazy val empty: StringFileNode = StringFileNode("")
}

case class LinesFileNode(override val readLines: Vector[String],
                         override val name: String = "<lines>",
                         override val encoding: Charset = FileUtils.DefaultEncoding,
                         lineSeparator: String = "\n",
                         trailingNewline: Boolean = true)
    extends AbstractVirtualFileNode(name, encoding) {
  override def readString: String =
    FileUtils.linesToString(readLines, lineSeparator, trailingNewline)
}

object LinesFileNode {
  def withName(name: String, lines: Vector[String]): LinesFileNode = {
    LinesFileNode(lines, name)
  }
}
