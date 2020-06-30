package wdlTools.eval

import java.io.IOException
import java.nio.file._

import wdlTools.syntax.TextSource
import wdlTools.util.{FileSource, NoSuchProtocolException, Options, Util}

import scala.jdk.CollectionConverters._
import scala.util.Random

// Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
case class IoSupp(opts: Options, evalCfg: EvalConfig, docSource: FileSource) {
  def getFileSource(uri: String, text: TextSource): FileSource = {
    try {
      evalCfg.fileResolver.resolve(uri)
    } catch {
      case e: NoSuchProtocolException =>
        throw new EvalException(e.getMessage, text, docSource)
    }
  }

  def size(pathOrUri: String, text: TextSource): Long = {
    val file = getFileSource(pathOrUri, text)
    try {
      file.size
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error getting size of ${pathOrUri}, msg=${e.getMessage}",
                                text,
                                docSource)
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
              docSource
          )
      }
    }
  }

  // Download `pathOrUri` to `dest`. If `pathOrUri` is already a local file, this
  // is a copy operation (unless `pathOrUri and `dest` are the same, in which case
  // this is a noop).
  def downloadFile(pathOrUri: String,
                   dest: Path,
                   overwrite: Boolean = false,
                   text: TextSource): Path = {
    val realPath = if (!Files.exists(dest)) {
      Util.createDirectories(dest.getParent)
      dest
    } else if (Files.isDirectory(dest)) {
      dest.toRealPath()
    } else if (overwrite) {
      opts.logger.warning(s"Deleting existing file ${dest}")
      Files.delete(dest)
      dest
    } else {
      throw new EvalException(
          s"File ${dest} already exists and overwrite = false",
          text,
          docSource
      )
    }
    val file = getFileSource(pathOrUri, text)
    try {
      file.localize(realPath)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error downloading file ${pathOrUri}, msg=${e.getMessage}",
                                text,
                                docSource)
    }
  }

  // Read the contents of the file/URI and return a string
  def readFile(pathOrUri: String, text: TextSource): String = {
    val file = getFileSource(pathOrUri, text)
    try {
      file.readString
    } catch {
      case e: Throwable =>
        throw new EvalException(s"error reading file ${pathOrUri}, msg=${e.getMessage}",
                                text,
                                docSource)
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
                                docSource)
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
