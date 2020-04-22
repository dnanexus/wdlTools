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

  def fromName(name: String): TypeCheckingRegime.Value = {
    val x = this.values.find(x => x.toString.toLowerCase() == name.toLowerCase())
    x.get
  }
}
import Verbosity._

/**
  * Common configuration options.
  * @param localDirectories local directories to search for imports.
  * @param followImports whether to follow imports when parsing.
  * @param verbosity verbosity level.
  * @param antlr4Trace whether to turn on tracing in the ANTLR4 parser.
  */
case class Options(localDirectories: Vector[Path] = Vector.empty,
                   followImports: Boolean = false,
                   verbosity: Verbosity = Normal,
                   antlr4Trace: Boolean = false,
                   typeChecking: TypeCheckingRegime.Value = TypeCheckingRegime.Moderate) {

  def getURL(pathOrUrl: String): URL = {
    Util.getURL(pathOrUrl, localDirectories)
  }
}
