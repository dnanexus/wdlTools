package wdlTools.eval

import java.nio.charset.StandardCharsets._
import java.nio.file.{Files, Paths}

//import java.nio.file.{Files, FileSystems, Paths, PathMatcher}
//import scala.collection.JavaConverters._

import wdlTools.util.Options
import wdlTools.eval.WdlValues._


case class IoFunctions(opts : Options, evalCfg : ExprEvalConfig) {

  // Functions that (possibly) necessitate I/O operation (on local, network, or cloud filesystems)
  private def readLocalFile(p : Path) : WV_String = {
    if (!Files.exists(p))
      throw new RuntimeException(s"File ${p} not found")

    // TODO: Check that the file isn't over 256 MiB.
    val content = String(Files.readAllBytes(p), UTF_8)
    WV_String(content)
  }

  private def readHttpAddr(url : URL) : WV_String = ???

  // Read the contents of the file/URL and return a string
  //
  // This may be a binary file that does not lend itself to splitting into lines.
  // Hence, we aren't using the Source module.
  def readFile(pathOrUrl: String) :  WV_String = {
    if (pathOrUrl.contains("://")) {
      val url = URL(pathOrUrl)
      val content = url.getProtocol match {
        case "http" | "https"  => readHttpAddr(url)
        case "file"  => readLocalFile(Paths.get(url.getPath()))
        case _       => throw new RuntimeException(s"unknown protocol in URL ${url}")
      }
      return content
    }

    // This is a local file
    val p = Paths.get(pathOrUrl)
    readLocalFile(p)
  }


  /**
    * Write "content" to the specified "path" location
    */
  def writeFile(pathOrUrl: String, content: String): WV_File = {
    if (pathOrUrl.contains("://")) {
        throw new AppInternalException(
            s"writeFile: implemented only for local files (${pathOrUrl})"
        )
    }

    val p = Paths.get(pathOrUrl)
    Utils.writeFileContent(p, content)
    WV_File(path)
  }

  /**
    * Glob files and directories using the provided pattern.
    * @return the list of globbed paths
    */
  def glob(pattern: String): Vector[String] = {
    Utils.appletLog(config.verbose, s"glob(${pattern})")
    val baseDir = config.homeDir
    val matcher: PathMatcher = FileSystems
      .getDefault()
      .getPathMatcher(s"glob:${baseDir.toString}/${pattern}")
    val retval =
      if (!Files.exists(baseDir)) {
        Seq.empty[String]
      } else {
        val files = Files
          .walk(baseDir)
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .filter(matcher.matches(_))
          .map(_.toString)
          .toSeq
        files.sorted
      }
    Utils.appletLog(config.verbose, s"""glob results=${retval.mkString("\n")}""")
    Future(retval)
  }

  /**
    * Return true if path points to a directory, false otherwise
    */
  override def isDirectory(path: String): Future[Boolean] = {
    Furl.parse(path) match {
      case FurlLocal(localPath) =>
        val p = Paths.get(localPath)
        Future(p.toFile.isDirectory)
      case fdx: FurlDx =>
        throw new AppInternalException(
            s"isDirectory: cannot be applied to non local file (${path})"
        )
    }
  }

  /**
    * Return the size of the file located at "path"
    */
  override def size(path: String): Future[Long] = {
    Furl.parse(path) match {
      case FurlLocal(localPath) =>
        val p = Paths.get(localPath)
        Future(p.toFile.length)
      case fdx: FurlDx =>
        val ssize: Long = pathFunctions.fileInfoDir.get(fdx.dxFile.id) match {
          case None =>
            // perform an API call to get the size
            fdx.dxFile.describe().size
          case Some((_, desc)) =>
            desc.size
        }
        Future(ssize)
    }
  }
}
