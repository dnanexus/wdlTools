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
  * @param localDirectories local directories to search for imports.
  * @param followImports whether to follow imports when parsing.
  * @param verbosity verbosity level.
  * @param antlr4Trace  whether to turn on tracing in the ANTLR4 parser.
  */
class Options(val localDirectories: Vector[Path] = Vector.empty,
              val followImports: Boolean = false,
              val verbosity: Verbosity.Verbosity = Verbosity.Normal,
              val antlr4Trace: Boolean = false) {

  def getUrl(pathOrUrl: String, mustExist: Boolean = true): URL = {
    Util.getUrl(pathOrUrl, localDirectories, mustExist)
  }
}

case class BasicOptions(override val localDirectories: Vector[Path] = Vector.empty,
                        override val followImports: Boolean = false,
                        override val verbosity: Verbosity.Verbosity = Verbosity.Normal,
                        override val antlr4Trace: Boolean = false)
    extends Options(localDirectories, followImports, verbosity, antlr4Trace)
