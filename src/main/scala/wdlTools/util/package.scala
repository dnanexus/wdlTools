package wdlTools.util

/**
  * Common configuration options.
  *
  * localDirectories local directories to search for imports.
  * followImports whether to follow imports when parsing.
  * verbosity verbosity level.
  * antlr4Trace  whether to turn on tracing in the ANTLR4 parser.
  */
trait Options {
  val fileResolver: FileSourceResolver
  val followImports: Boolean
  val logger: Logger
  val antlr4Trace: Boolean
}

case class BasicOptions(fileResolver: FileSourceResolver = FileSourceResolver.create(),
                        followImports: Boolean = false,
                        logger: Logger = Logger.Normal,
                        antlr4Trace: Boolean = false)
    extends Options
