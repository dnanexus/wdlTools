package wdlTools.util

import java.io.{File, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileAlreadyExistsException,
  FileVisitResult,
  Files,
  Path,
  Paths,
  SimpleFileVisitor
}

import sun.security.action.GetPropertyAction

import scala.io.{Codec, Source}

object FileUtils {
  val FILE_SCHEME: String = "file"
  val HTTP_SCHEME: String = "http"
  val HTTPS_SCHEME: String = "https"
  private val uriRegexp = "^(.+?)://.+".r
  // the spec states that WDL files must use UTF8 encoding
  val DefaultEncoding: Charset = Codec.UTF8.charSet
  val DefaultLineSeparator: String = "\n"

  def tempDir: Path = {
    try {
      Paths.get(GetPropertyAction.privilegedGetProperty("java.io.tmpdir"))
    } catch {
      case _: Throwable => Paths.get("/tmp")
    }
  }

  def absolutePath(path: Path): Path = {
    if (Files.exists(path)) {
      path.toRealPath()
    } else {
      path.toAbsolutePath
    }
  }

  /**
    * Converts a String to a Path. Use this instead of `Paths.get()` if you want a relative
    * path to remain relative (`Paths.get()` may convert a relative path to an absolute path
    * relative to cwd).
    * @param path the path string
    * @return
    */
  def getPath(path: String): Path = {
    new File(path).toPath
  }

  def getUriScheme(pathOrUri: String): Option[String] = {
    pathOrUri match {
      case uriRegexp(scheme) => Some(scheme)
      case _                 => None
    }
  }

  def changeFileExt(fileName: String, dropExt: String = "", addExt: String = ""): String = {
    ((fileName, dropExt) match {
      case (fn, ext) if fn.length > 0 && fn.endsWith(ext) => fn.dropRight(dropExt.length)
      case (fn, _)                                        => fn
    }) + addExt
  }

  def replaceFileSuffix(path: Path, suffix: String): String = {
    replaceFileSuffix(path.getFileName.toString, suffix)
  }

  // Add a suffix to a filename, before the regular suffix. For example:
  //  xxx.wdl -> xxx.simplified.wdl
  def replaceFileSuffix(fileName: String, suffix: String): String = {
    val index = fileName.lastIndexOf('.')
    val prefix = if (index >= 0) {
      fileName.substring(0, index)
    } else {
      ""
    }
    changeFileExt(prefix, addExt = suffix)
  }

  def readStdinContent(encoding: Charset = DefaultEncoding): String = {
    Source.fromInputStream(System.in, encoding.name).getLines().mkString("\n")
  }

  /**
    * Reads the entire contents of a file as a string. Line endings are not stripped or
    * converted.
    * @param path file path
    * @return file contents as a string
    */
  def readFileContent(path: Path, encoding: Charset = DefaultEncoding): String = {
    new String(Files.readAllBytes(path), encoding)
  }

  /**
    * Reads all the lines from a file and returns them as a Vector of strings. Lines have
    * line-ending characters ([\r\n]) stripped off. Notably, there is no way to know if
    * the last line originaly ended with a newline.
    * @param path the path to the file
    * @return a Seq of the lines from the file
    */
  def readFileLines(path: Path, encoding: Charset = DefaultEncoding): Vector[String] = {
    val source = Source.fromFile(path.toString, encoding.name)
    try {
      source.getLines.toVector
    } finally {
      source.close()
    }
  }

  def checkOverwrite(path: Path, overwrite: Boolean): Boolean = {
    if (Files.exists(path)) {
      if (!overwrite) {
        throw new FileAlreadyExistsException(s"${path} exists and overwrite = false")
      } else if (Files.isDirectory(path)) {
        throw new FileAlreadyExistsException(s"${path} already exists as a directory")
      }
      true
    } else {
      false
    }
  }

  /**
    * Write a String to a file.
    * @param content the string to write
    * @param path the path of the file
    * @param overwrite whether to overwrite an existing file
    * @param makeExecutable whether to set the file's executable flag
    */
  def writeFileContent(path: Path,
                       content: String,
                       overwrite: Boolean = true,
                       makeExecutable: Boolean = false): Path = {
    checkOverwrite(path, overwrite)
    val parent = createDirectories(path.getParent)
    val resolved = parent.resolve(path.getFileName)
    Files.write(resolved, content.getBytes(DefaultEncoding))
    if (makeExecutable) {
      resolved.toFile.setExecutable(true)
    }
    resolved
  }

  /**
    * Copy a source file to a destination file/directory. If `linkOk` is true, and the
    * current system supports symplinks, creates a symlink rather than copying the file.
    * @param source the source file
    * @param dest the destination file
    * @param linkOk whether it is okay to create a symbolic link rather than copy the file
    * @return whether the result was a symlink (true) or copy (false)
    */
  def copyFile(source: Path,
               dest: Path,
               overwrite: Boolean = true,
               linkOk: Boolean = false): Boolean = {
    checkOverwrite(dest, overwrite)
    if (linkOk) {
      try {
        Files.createSymbolicLink(source, dest)
        return true
      } catch {
        case _: IOException => ()
      }
    }
    Files.copy(source, dest)
    false
  }

  /**
    * Files.createDirectories does not handle links. This function searches starting from dir to find the
    * first parent directory that exists, converts that to a real path, resolves the subdirectories, and
    * then creates them.
    * @param dir the directory path to create
    * @return the fully resolved and existing Path
    */
  def createDirectories(dir: Path): Path = {
    if (Files.exists(dir)) {
      if (Files.isDirectory(dir)) {
        return dir.toRealPath()
      } else {
        throw new FileAlreadyExistsException(dir.toString)
      }
    }
    var parent: Path = dir
    var subdirs: Vector[String] = Vector.empty
    while (parent != null && !Files.exists(parent)) {
      subdirs = subdirs :+ parent.getFileName.toString
      parent = parent.getParent
    }
    if (parent == null) {
      throw new RuntimeException(s"None of the parents of ${dir} exist")
    }
    val realDir = Paths.get(parent.toRealPath().toString, subdirs: _*)
    Files.createDirectories(realDir)
    realDir
  }

  private case class CopyDirFileVisitor(sourceDir: Path, targetDir: Path)
      extends SimpleFileVisitor[Path] {
    override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
      Files.copy(file, targetDir.resolve(sourceDir.relativize(file)))
      FileVisitResult.CONTINUE
    }

    override def preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult = {
      Files.createDirectory(targetDir.resolve(sourceDir.relativize(dir)))
      FileVisitResult.CONTINUE
    }
  }

  /**
    * Copy a source directory to a destination directory. If `linkOk` is true, and the
    * current system supports symplinks, creates a symlink rather than copying the file.
    * @param sourceDir the source file
    * @param targetDir the destination file
    * @param linkOk whether it is okay to create a symbolic link rather than copy the file
    * @return whether the result was a symlink (true) or copy (false)
    */
  def copyDirectory(sourceDir: Path,
                    targetDir: Path,
                    overwrite: Boolean = true,
                    linkOk: Boolean = false): Boolean = {
    if (Files.exists(targetDir)) {
      if (overwrite) {
        deleteRecursive(targetDir)
      } else {
        throw new FileAlreadyExistsException(s"${targetDir} exists and overwrite = false")
      }
    }
    if (linkOk) {
      try {
        Files.createSymbolicLink(sourceDir, targetDir)
        return true
      } catch {
        case _: IOException => ()
      }
    }
    createDirectories(targetDir)
    Files.walkFileTree(sourceDir, CopyDirFileVisitor(sourceDir, targetDir))
    false
  }

  def deleteRecursive(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.walkFileTree(
            path.toRealPath(),
            new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
                Files.delete(file)
                FileVisitResult.CONTINUE
              }

              override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
                Files.delete(dir)
                FileVisitResult.CONTINUE
              }
            }
        )
      } else {
        Files.delete(path)
      }
    }
  }

  sealed abstract class ArchiveType(val name: String, val extensions: Set[String])

  object ArchiveType {
    case object Tar extends ArchiveType("tar", Set(".tar"))
    case object Tgz extends ArchiveType("tgz", Set(".tgz", ".tar.gz"))
    case object Zip extends ArchiveType("zip", Set(".zip"))

    val All = Vector(Tar, Tgz, Zip)

    def fromExtension(path: Path): ArchiveType = {
      val pathStr = path.getFileName.toString
      All
        .collectFirst {
          case a if a.extensions.exists(suffix => pathStr.endsWith(suffix)) => a
        }
        .getOrElse(
            throw new Exception(s"Not a supported archive file: ${path}")
        )
    }
  }

  /**
    * Unpack an archive file. For now we do this in a (platform-dependent) native manner, using
    * a subprocess to call the appropriate system tool based on the file extension.
    * @param archiveFile the archive file to unpack
    * @param targetDir the directory to which to unpack the file
    */
  def unpackArchive(archiveFile: Path,
                    targetDir: Path,
                    archiveType: Option[ArchiveType] = None,
                    overwrite: Boolean = true,
                    logger: Logger = Logger.Quiet): Unit = {
    if (Files.exists(targetDir)) {
      if (overwrite) {
        deleteRecursive(targetDir)
      } else {
        throw new FileAlreadyExistsException(s"${targetDir} exists and overwrite = false")
      }
    }
    createDirectories(targetDir)
    val command = archiveType.getOrElse(ArchiveType.fromExtension(archiveFile)) match {
      case ArchiveType.Tar =>
        s"tar -xf '${archiveFile}' -C '${targetDir}'"
      case ArchiveType.Tgz =>
        s"tar -xzf '${archiveFile}' -C '${targetDir}'"
      case ArchiveType.Zip =>
        s"unzip '${archiveFile}' -d '${targetDir}'"
    }
    SysUtils.execCommand(command, logger = logger)
  }
}
