package wdlTools.eval

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file._

import wdlTools.syntax.SourceLocation
import dx.util.{EvalPaths, FileNode, FileSourceResolver, FileUtils, Logger, NoSuchProtocolException}

import scala.jdk.CollectionConverters._

// Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
case class IoSupport(paths: EvalPaths,
                     fileResolver: FileSourceResolver = FileSourceResolver.get,
                     logger: Logger = Logger.get,
                     encoding: Charset = FileUtils.DefaultEncoding) {
  def getFileSource(uri: String, loc: SourceLocation): FileNode = {
    try {
      fileResolver.resolve(uri)
    } catch {
      case e: NoSuchProtocolException =>
        throw new EvalException(e.getMessage, loc)
    }
  }

  def size(pathOrUri: String, loc: SourceLocation): Long = {
    val file = getFileSource(pathOrUri, loc)
    try {
      file.size
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error getting size of ${pathOrUri}, msg=${e.getMessage}", loc)
    }
  }

  // Create an empty file if the given path does not already exist - used by stdout() and stderr()
  def ensureFileExists(path: Path, name: String, loc: SourceLocation): Unit = {
    val file = path.toFile
    if (!file.exists()) {
      try {
        file.createNewFile()
      } catch {
        case e: IOException =>
          throw new EvalException(
              s"${name} file ${file} does not exist and cannot be created: ${e.getMessage}",
              loc
          )
      }
    }
  }

  // Read the contents of the file/URI and return a string
  def readFile(pathOrUri: String, loc: SourceLocation): String = {
    val file = getFileSource(pathOrUri, loc)
    try {
      file.readString
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error reading file ${pathOrUri}, msg=${e.getMessage}", loc)
    }
  }

  /**
    * Write "content" to the specified "path" location
    */
  def writeFile(p: Path, content: String, loc: SourceLocation): Unit = {
    try {
      Files.write(p, content.getBytes(encoding))
    } catch {
      case t: Throwable =>
        throw new EvalException(s"Error wrting content to file ${p}: ${t.getMessage}", loc)
    }
  }

  /**
    * Glob files and directories using the provided pattern.
    * @return the list of globbed paths
    */
  def glob(pattern: String): Vector[String] = {
    logger.trace(s"glob(${pattern})")
    val baseDir = paths.getWorkDir(ensureExists = true).asJavaPath
    val retval =
      if (!Files.exists(baseDir)) {
        Vector.empty[String]
      } else {
        val globPath = baseDir.resolve(pattern).normalize()
        val matcher = FileSystems.getDefault.getPathMatcher(s"glob:${globPath.toString}")
        Files
          .walk(baseDir)
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .filter(matcher.matches)
          .map(_.toString)
          .toVector
          .sorted
      }
    logger.trace(s"""glob results=${retval.mkString("\n")}""")
    retval
  }

  def mkTempFile(prefix: String = "wdlTools", suffix: String = ""): Path = {
    Files.createTempFile(paths.getTempDir(true).asJavaPath, prefix, suffix)
  }
}
