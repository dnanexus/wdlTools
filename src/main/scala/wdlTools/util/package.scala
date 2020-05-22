package wdlTools.util

import java.net.URL
import java.nio.file.Path

/**
  * Enumeration for verbosity level.
  * The values are in increasing order, so that they can be compared using integer comparison
  * operators, e.g. `if (verbosity > Normal) { println("debugging") }`.
  */
object Verbosity extends Enumeration {
  type Verbosity = Value
  val Quiet, Normal, Verbose = Value
}

object TypeCheckingRegime extends Enumeration {
  type TypeCheckingRegime = Value
  val Strict, Moderate, Lenient = Value
}

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
  val verbosity: Verbosity.Verbosity
  val antlr4Trace: Boolean

  def getUrl(pathOrUrl: String, mustExist: Boolean = true): URL = {
    Util.getUrl(pathOrUrl, localDirectories, mustExist)
  }
}

case class BasicOptions(localDirectories: Vector[Path] = Vector.empty,
                        followImports: Boolean = false,
                        verbosity: Verbosity.Verbosity = Verbosity.Normal,
                        antlr4Trace: Boolean = false)
    extends Options
