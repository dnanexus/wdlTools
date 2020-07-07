package wdlTools.eval

import java.io.IOException
import java.nio.file._

import wdlTools.syntax.SourceLocation
import wdlTools.util.{FileSource, NoSuchProtocolException, Options, Util}

import scala.jdk.CollectionConverters._
import scala.util.Random

// Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
case class IoSupp(opts: Options, evalCfg: EvalConfig) {
  def getFileSource(uri: String, loc: SourceLocation): FileSource = {
    try {
      evalCfg.fileResolver.resolve(uri)
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

  // Download `pathOrUri` to `dest`. If `pathOrUri` is already a local file, this
  // is a copy operation (unless `pathOrUri and `dest` are the same, in which case
  // this is a noop).
  def downloadFile(pathOrUri: String,
                   destFile: Option[Path] = None,
                   destDir: Option[Path] = None,
                   overwrite: Boolean = false,
                   loc: SourceLocation): Path = {
    val src = getFileSource(pathOrUri, loc)
    val dest = destFile.getOrElse(
        destDir.getOrElse(Paths.get(".")).resolve(src.fileName)
    )
    val realPath = if (!Files.exists(dest)) {
      Util.createDirectories(dest.getParent)
      dest.toAbsolutePath
    } else if (Files.isDirectory(dest)) {
      throw new EvalException(
          s"${dest} already exists as a directory - can't overwrite",
          loc
      )
    } else if (overwrite) {
      val realPath = dest.toRealPath()
      opts.logger.warning(s"Deleting existing file ${realPath}")
      Files.delete(realPath)
      realPath
    } else {
      throw new EvalException(
          s"File ${dest} already exists and overwrite = false",
          loc
      )
    }
    try {
      src.localize(realPath)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error downloading file ${pathOrUri}, msg=${e.getMessage}", loc)
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
      Files.write(p, content.getBytes(evalCfg.encoding))
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
    if (opts.logger.isVerbose) {
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
    if (opts.logger.isVerbose) {
      System.out.println(s"""glob results=${retval.mkString("\n")}""")
    }
    retval
  }

  def mkTempFile(): Path = {
    val rndName = Random.alphanumeric.take(8).mkString("")
    evalCfg.tmpDir.resolve(rndName)
  }
}
