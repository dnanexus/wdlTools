package wdlTools.syntax

import java.net.URI
import java.nio.file.Path

import wdlTools.syntax
import wdlTools.util.Util.Verbosity._
import wdlTools.util.Util.{getLocalPath, readFromFile}

object Util {

  /**
    * Common configuration options used by syntax classes.
    * @param localDirectories local directories to search for imports.
    * @param verbosity verbosity level.
    * @param antlr4Trace whether to turn on tracing in the ANTLR4 parser.
    */
  case class Options(localDirectories: Seq[Path],
                     verbosity: Verbosity = Normal,
                     antlr4Trace: Boolean = false)

  /**
    * Reads the contents from a URI, which may be a local file or a remote (http(s)) URL.
    * @param uri the URI to reAd
    * @param conf the options
    * @return a tuple (localPath, contents), where localPath is
    */
  def readFromUri(uri: URI, conf: Options): String = {
    val uriLocalPath = getLocalPath(uri)
    uri.getScheme match {
      case null | "" | "file" => readFromFile(uriLocalPath)
      case _ =>
        syntax.FetchURL(conf).apply(syntax.URL(uri.toString))
    }
  }
}
