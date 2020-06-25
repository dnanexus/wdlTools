package wdlTools.util

import java.net.URL
import java.nio.file.Path

/**
  * Common configuration options.
  *
  * localDirectories local directories to search for imports.
  * followImports whether to follow imports when parsing.
  * verbosity verbosity level.
  * antlr4Trace  whether to turn on tracing in the ANTLR4 parser.
  */
trait Options {
  val localDirectories: Vector[Path]
  val followImports: Boolean
  val logger: Logger
  val antlr4Trace: Boolean

  def getUrl(pathOrUrl: String, mustExist: Boolean = true): URL = {
    Util.getUrl(pathOrUrl, localDirectories, mustExist)
  }
}

case class BasicOptions(localDirectories: Vector[Path] = Vector.empty,
                        followImports: Boolean = false,
                        logger: Logger = Logger.Normal,
                        antlr4Trace: Boolean = false)
    extends Options
