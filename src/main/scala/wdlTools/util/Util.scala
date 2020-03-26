package wdlTools.util

import java.net.URI
import java.nio.file.{Path, Paths}

import com.typesafe.config.ConfigFactory

/**
  * Enumeration for verbosity level.
  * The values are in increasing order, so that they can be compared using integer comparison
  * operators, e.g. `if (verbosity > Normal) { println("debugging") }`.
  */
object Verbosity extends Enumeration {
  type Verbosity = Value
  val Quiet, Normal, Verbose = Value
}
import Verbosity._

/**
  * Common configuration options used by syntax classes.
  * @param localDirectories local directories to search for imports.
  * @param verbosity verbosity level.
  * @param antlr4Trace whether to turn on tracing in the ANTLR4 parser.
  */
case class Options(localDirectories: Seq[Path] = Seq.empty,
                   verbosity: Verbosity = Normal,
                   antlr4Trace: Boolean = false)

object Util {

  /**
    * The current wdlTools version.
    * @return
    */
  def getVersion: String = {
    val config = ConfigFactory.load("application.conf")
    config.getString("wdlTools.version")
  }

  /**
    * Determines the local path to a URI's file. The path will be the URI's file name relative to the parent; the
    * current working directory is used as the parent unless `parent` is specified. If the URI indicates a local path
    * and `ovewrite` is `true`, then the absolute local path is returned unless `parent` is specified.
    *
    * @param uri a URI, which might be a local path, a file:// uri, or an http(s):// uri)
    * @param parent The directory to which the local file should be made relative
    * @param selfOk Whether it is allowed to return the absolute path of a URI that is a local file, rather than making
    *               it relative to the current directory; ignored if `parent` is defined
    * @return The Path to the local file
    */
  def getLocalPath(uri: URI, parent: Option[Path] = None, selfOk: Boolean = true): Path = {
    uri.getScheme match {
      case null | "" | "file" =>
        val path = Paths.get(uri.getPath)

        if (parent.isDefined) {
          parent.get.resolve(path.getFileName)
        } else if (selfOk) {
          path.toAbsolutePath
        } else {
          Paths.get("").toAbsolutePath.resolve(path.getFileName)
        }
      case _ =>
        parent.getOrElse(Paths.get("")).resolve(Paths.get(uri.getPath).getFileName)
    }
  }

  /**
    * Reads the lines from a file and concatenates the lines using the system line separator.
    * @param path the path to the file
    * @return
    */
  def readFromFile(path: Path): String = {
    val source = io.Source.fromFile(path.toString)
    try source.getLines.mkString(System.lineSeparator())
    finally source.close()
  }
}
