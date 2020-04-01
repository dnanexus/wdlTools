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

// a path to a file or an http location
//
// examples:
//   http://google.com/A.txt
//   https://google.com/A.txt
//   file://A/B.txt
//   foo.txt
case class URL(addr: String)

// source location in a WDL program. We add it to each syntax element
// so we could do accurate error reporting.
//
// line: line number
// col : column number
// URL:  original file or web URL
//
case class TextSource(line : Int,
                      col : Int,
                      url : URL)

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

  /**
    * Pretty prints a Scala value similar to its source represention.
    * Particularly useful for case classes.
    * @see https://gist.github.com/carymrobbins/7b8ed52cd6ea186dbdf8
    * @param a - The value to pretty print.
    * @param indentSize - Number of spaces for each indent.
    * @param maxElementWidth - Largest element size before wrapping.
    * @param depth - Initial depth to pretty print indents.
    * @return
    */
  def prettyPrint(a: Any,
                  indentSize: Int = 2,
                  maxElementWidth: Int = 30,
                  depth: Int = 0): String = {
    val indent = " " * depth * indentSize
    val fieldIndent = indent + (" " * indentSize)
    val thisDepth = prettyPrint(_: Any, indentSize, maxElementWidth, depth)
    val nextDepth = prettyPrint(_: Any, indentSize, maxElementWidth, depth + 1)
    a match {
      // Make Strings look similar to their literal form.
      case s: String =>
        val replaceMap = Seq(
            "\n" -> "\\n",
            "\r" -> "\\r",
            "\t" -> "\\t",
            "\"" -> "\\\""
        )
        '"' + replaceMap.foldLeft(s) { case (acc, (c, r)) => acc.replace(c, r) } + '"'
      // For an empty Seq just use its normal String representation.
      case xs: Seq[_] if xs.isEmpty => xs.toString()
      case xs: Seq[_]               =>
        // If the Seq is not too long, pretty print on one line.
        val resultOneLine = xs.map(nextDepth).toString()
        if (resultOneLine.length <= maxElementWidth) return resultOneLine
        // Otherwise, build it with newlines and proper field indents.
        val result = xs.map(x => s"\n$fieldIndent${nextDepth(x)}").toString()
        result.substring(0, result.length - 1) + "\n" + indent + ")"
      // Product should cover case classes.
      case p: Product =>
        val prefix = p.productPrefix
        // We'll use reflection to get the constructor arg names and values.
        val cls = p.getClass
        val fields = cls.getDeclaredFields.filterNot(_.isSynthetic).map(_.getName)
        val values = p.productIterator.toSeq
        // If we weren't able to match up fields/values, fall back to toString.
        if (fields.length != values.length) return p.toString
        fields.zip(values).toList match {
          // If there are no fields, just use the normal String representation.
          case Nil => p.toString
          // If there is just one field, let's just print it as a wrapper.
          case (_, value) :: Nil => s"$prefix(${thisDepth(value)})"
          // If there is more than one field, build up the field names and values.
          case kvps =>
            val prettyFields = kvps.map { case (k, v) => s"$fieldIndent$k = ${nextDepth(v)}" }
            // If the result is not too long, pretty print on one line.
            val resultOneLine = s"$prefix(${prettyFields.mkString(", ")})"
            if (resultOneLine.length <= maxElementWidth) return resultOneLine
            // Otherwise, build it with newlines and proper field indents.
            s"$prefix(\n${prettyFields.mkString(",\n")}\n$indent)"
        }
      // If we haven't specialized this type, just use its toString.
      case _ => a.toString
    }
  }
}
