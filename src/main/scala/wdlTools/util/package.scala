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

object Util {
  def exceptionToString(e: Throwable): String = {
    val sw = new java.io.StringWriter
    e.printStackTrace(new java.io.PrintWriter(sw))
    sw.toString
  }

  def errorMessage(message: String, exception: Option[Throwable]): String = {
    val exStr = exception.map(exceptionToString)
    (message, exStr) match {
      case (s, Some(e)) if s.nonEmpty =>
        s"""${message}
           |${e}""".stripMargin
      case ("", Some(e)) => e
      case (s, None)     => s
    }
  }

  /**
    * Pretty formats a Scala value similar to its source represention.
    * Particularly useful for case classes.
    * @see https://gist.github.com/carymrobbins/7b8ed52cd6ea186dbdf8
    * @param a The value to pretty print.
    * @param indentSize Number of spaces for each indent.
    * @param maxElementWidth Largest element size before wrapping.
    * @param depth Initial depth to pretty print indents.
    * @return the formatted object as a String
    * TODO: add color
    */
  def prettyFormat(a: Any,
                   indentSize: Int = 2,
                   maxElementWidth: Int = 30,
                   depth: Int = 0,
                   callback: Option[Product => Option[String]] = None): String = {
    val indent = " " * depth * indentSize
    val fieldIndent = indent + (" " * indentSize)
    val thisDepth = prettyFormat(_: Any, indentSize, maxElementWidth, depth, callback)
    val nextDepth = prettyFormat(_: Any, indentSize, maxElementWidth, depth + 1, callback)
    a match {
      // Make Strings look similar to their literal form.
      case s: String =>
        val replaceMap = Seq(
            "\n" -> "\\n",
            "\r" -> "\\r",
            "\t" -> "\\t",
            "\"" -> "\\\""
        )
        val buf = replaceMap.foldLeft(s) { case (acc, (c, r)) => acc.replace(c, r) }
        s""""${buf}""""
      // For an empty Seq just use its normal String representation.
      case xs: Seq[_] if xs.isEmpty => xs.toString()
      case xs: Seq[_]               =>
        // If the Seq is not too long, pretty print on one line.
        val resultOneLine = xs.map(nextDepth).toString()
        if (resultOneLine.length <= maxElementWidth) return resultOneLine
        // Otherwise, build it with newlines and proper field indents.
        val result = xs.map(x => s"\n$fieldIndent${nextDepth(x)}").toString()
        result.substring(0, result.length - 1) + "\n" + indent + ")"
      case Some(x) =>
        s"Some(\n$fieldIndent${prettyFormat(x, indentSize, maxElementWidth, depth + 1, callback)}\n$indent)"
      case None => "None"
      // Product should cover case classes.
      case p: Product =>
        callback.map(_(p)) match {
          case Some(Some(s)) => s
          case _ =>
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
        }
      // If we haven't specialized this type, just use its toString.
      case _ => a.toString
    }
  }
}
