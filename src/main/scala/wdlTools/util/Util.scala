package wdlTools.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileAlreadyExistsException,
  FileVisitResult,
  Files,
  Path,
  Paths,
  SimpleFileVisitor
}
import java.util.Base64
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import scala.concurrent.{Await, Future, TimeoutException, blocking, duration}
import scala.concurrent.ExecutionContext.Implicits._
import scala.io.{Codec, Source}
import scala.sys.process.{Process, ProcessLogger}

object Util {
  val FILE_SCHEME: String = "file"
  val HTTP_SCHEME: String = "http"
  val HTTPS_SCHEME: String = "https"
  private val uriRegexp = "^(.+?)://.+".r
  // the spec states that WDL files must use UTF8 encoding
  val DefaultEncoding: Charset = Codec.UTF8.charSet
  val DefaultLineSeparator: String = "\n"

  def absolutePath(path: Path): Path = {
    if (Files.exists(path)) {
      path.toRealPath()
    } else {
      path.toAbsolutePath
    }
  }

  /**
    * Converts a String to a Path. Use this instead of `Paths.get()` if you want a relative
    * path to remain relative (`Paths.get()` converts a relative path to an absolute path
    * relative to cwd).
    * @param path the path string
    * @return
    */
  def getPath(path: String): Path = {
    new File(path).toPath
  }

  def getUriScheme(pathOrUri: String): Option[String] = {
    pathOrUri match {
      case uriRegexp(scheme) => Some(scheme)
      case _                 => None
    }
  }

  def changeFileExt(fileName: String, dropExt: String = "", addExt: String = ""): String = {
    ((fileName, dropExt) match {
      case (fn, ext) if fn.length > 0 && fn.endsWith(ext) => fn.dropRight(dropExt.length)
      case (fn, _)                                        => fn
    }) + addExt
  }

  // Add a suffix to a filename, before the regular suffix. For example:
  //  xxx.wdl -> xxx.simplified.wdl
  def replaceFileSuffix(fileName: String, suffix: String = ""): String = {
    val index = fileName.lastIndexOf('.')
    val prefix = if (index >= 0) {
      fileName.substring(0, index)
    } else {
      ""
    }
    changeFileExt(prefix, suffix)
  }

  /**
    * Reads the entire contents of a file as a string. Line endings are not stripped or
    * converted.
    * @param path file path
    * @return file contents as a string
    */
  def readFileContent(path: Path, encoding: Charset = DefaultEncoding): String = {
    new String(Files.readAllBytes(path), encoding)
  }

  /**
    * Reads all the lines from a file and returns them as a Vector of strings. Lines have
    * line-ending characters ([\r\n]) stripped off. Notably, there is no way to know if
    * the last line originaly ended with a newline.
    * @param path the path to the file
    * @return a Seq of the lines from the file
    */
  def readFileLines(path: Path, encoding: Charset = DefaultEncoding): Vector[String] = {
    val source = Source.fromFile(path.toString, encoding.name)
    try {
      source.getLines.toVector
    } finally {
      source.close()
    }
  }

  def checkOverwrite(path: Path, overwrite: Boolean): Unit = {
    if (Files.exists(path)) {
      if (!overwrite) {
        throw new FileAlreadyExistsException(s"${path} exists and overwrite = false")
      } else if (Files.isDirectory(path)) {
        throw new FileAlreadyExistsException(s"${path} already exists as a directory")
      }
    }
  }

  /**
    * Write a String to a file.
    * @param content the string to write
    * @param path the path of the file
    * @param overwrite whether to overwrite an existing file
    */
  def writeFileContent(path: Path, content: String, overwrite: Boolean = true): Unit = {
    if (!overwrite && Files.exists(path)) {
      throw new Exception(s"File already exists: ${path}")
    }
    val parent = createDirectories(path.getParent)
    Files.write(parent.resolve(path.getFileName), content.getBytes(DefaultEncoding))
  }

  /**
    * Files.createDirectories does not handle links. This function searches starting from dir to find the
    * first parent directory that exists, converts that to a real path, resolves the subdirectories, and
    * then creates them.
    * @param dir the directory path to create
    * @return the fully resolved and existing Path
    */
  def createDirectories(dir: Path): Path = {
    if (Files.exists(dir)) {
      if (Files.isDirectory(dir)) {
        return dir.toRealPath()
      } else {
        throw new FileAlreadyExistsException(dir.toString)
      }
    }
    var parent: Path = dir
    var subdirs: Vector[String] = Vector.empty
    while (parent != null && !Files.exists(parent)) {
      subdirs = subdirs :+ parent.getFileName.toString
      parent = parent.getParent
    }
    if (parent == null) {
      throw new RuntimeException(s"None of the parents of ${dir} exist")
    }
    val realDir = Paths.get(parent.toRealPath().toString, subdirs: _*)
    Files.createDirectories(realDir)
    realDir
  }

  def deleteRecursive(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.walkFileTree(
            path.toRealPath(),
            new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
                Files.delete(file)
                FileVisitResult.CONTINUE
              }

              override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
                Files.delete(dir)
                FileVisitResult.CONTINUE
              }
            }
        )
      } else {
        Files.delete(path)
      }
    }
  }

  // From: https://gist.github.com/owainlewis/1e7d1e68a6818ee4d50e
  // By: owainlewis
  def gzipCompress(input: Array[Byte]): Array[Byte] = {
    val bos = new ByteArrayOutputStream(input.length)
    val gzip = new GZIPOutputStream(bos)
    gzip.write(input)
    gzip.close()
    val compressed = bos.toByteArray
    bos.close()
    compressed
  }

  def gzipDecompress(compressed: Array[Byte]): String = {
    val inputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))
    scala.io.Source.fromInputStream(inputStream).mkString
  }

  def gzipAndBase64Encode(buf: String): String = {
    val bytes = buf.getBytes
    val gzBytes = gzipCompress(bytes)
    Base64.getEncoder.encodeToString(gzBytes)
  }

  def base64DecodeAndGunzip(buf64: String): String = {
    val ba: Array[Byte] = Base64.getDecoder.decode(buf64.getBytes)
    gzipDecompress(ba)
  }

  /**
    * Given a multi-line string, determine the largest w such that each line
    * begins with at least w whitespace characters.
    * @param s the string to trim
    * @param ignoreEmptyLines ignore empty lines
    * @param lineSep character to use to separate lines in the returned String
    * @return tuple (lineOffset, colOffset, trimmedString) where lineOffset
    *  is the number of lines trimmed from the beginning of the string,
    *  colOffset is the number of whitespace characters trimmed from the
    *  beginning of the line containing the first non-whitespace character,
    *  and trimmedString is `s` with all all prefix and suffix whitespace
    *  trimmed, as well as `w` whitespace characters trimmed from the
    *  beginning of each line.
    *  @example
    *    val s = "   \n  hello\n   goodbye\n "
    *    stripLeadingWhitespace(s, false) => (1, 1, "hello\n  goodbye\n")
    *     stripLeadingWhitespace(s, true) => (1, 2, "hello\n goodbye")
    */
  def stripLeadingWhitespace(s: String,
                             ignoreEmptyLines: Boolean = true,
                             lineSep: String = System.lineSeparator()): (Int, Int, String) = {
    val lines = s.split("\r\n?|\n")
    val wsRegex = "^([ \t]*)$".r
    val nonWsRegex = "^([ \t]*)(.+)$".r
    val (lineOffset, content) = lines.foldLeft((0, Vector.empty[(String, String)])) {
      case ((lineOffset, content), wsRegex(txt)) =>
        if (content.isEmpty) {
          (lineOffset + 1, content)
        } else if (ignoreEmptyLines) {
          (lineOffset, content)
        } else {
          (lineOffset, content :+ (txt, ""))
        }
      case ((lineOffset, content), nonWsRegex(ws, txt)) => (lineOffset, content :+ (ws, txt))
    }
    if (content.isEmpty) {
      (lineOffset, 0, "")
    } else {
      val (whitespace, strippedLines) = content.unzip
      val colOffset = whitespace.map(_.length).min
      val strippedContent = (
          if (colOffset == 0) {
            strippedLines
          } else {
            // add back to each line any whitespace longer than colOffset
            strippedLines.zip(whitespace).map {
              case (line, ws) if ws.length > colOffset => ws.drop(colOffset) + line
              case (line, _)                           => line
            }
          }
      ).mkString(lineSep)
      (lineOffset, colOffset, strippedContent)
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

  // Run a child process and collect stdout and stderr into strings
  def execCommand(cmdLine: String,
                  timeout: Option[Int] = None,
                  quiet: Boolean = false): (String, String) = {
    val cmds = Seq("/bin/sh", "-c", cmdLine)
    val outStream = new StringBuilder()
    val errStream = new StringBuilder()
    val logger = ProcessLogger(
        (o: String) => { outStream.append(o ++ "\n") },
        (e: String) => { errStream.append(e ++ "\n") }
    )

    val p: Process = Process(cmds).run(logger, connectInput = false)
    timeout match {
      case None =>
        // blocks, and returns the exit code. Does NOT connect
        // the standard in of the child job to the parent
        val retcode = p.exitValue()
        if (retcode != 0) {
          if (!quiet) {
            System.err.println(s"STDOUT: ${outStream.toString()}")
            System.err.println(s"STDERR: ${errStream.toString()}")
          }
          throw new Exception(s"Error running command ${cmdLine}")
        }
      case Some(nSec) =>
        val f = Future(blocking(p.exitValue()))
        try {
          Await.result(f, duration.Duration(nSec, "sec"))
        } catch {
          case _: TimeoutException =>
            p.destroy()
            throw new Exception(s"Timeout exceeded (${nSec} seconds)")
        }
    }
    (outStream.toString(), errStream.toString())
  }

  /**
    * Simple bi-directional Map class.
    * @param keys map keys
    * @param values map values - must be unique, i.e. you must be able to map values -> keys without collisions
    * @tparam X keys Type
    * @tparam Y values Type
    */
  case class BiMap[X, Y](keys: Seq[X], values: Seq[Y]) {
    require(keys.size == values.size, "no 1 to 1 relation")
    private lazy val kvMap: Map[X, Y] = keys.zip(values).toMap
    private lazy val vkMap: Map[Y, X] = values.zip(keys).toMap

    def size: Int = keys.size

    def fromKey(x: X): Y = kvMap(x)

    def fromValue(y: Y): X = vkMap(y)

    def filterKeys(p: X => Boolean): BiMap[X, Y] = {
      BiMap.fromPairs(keys.zip(values).filter(item => p(item._1)))
    }
  }

  object BiMap {
    def fromPairs[X, Y](pairs: Seq[(X, Y)]): BiMap[X, Y] = {
      BiMap(pairs.map(_._1), pairs.map(_._2))
    }

    def fromMap[X, Y](map: Map[X, Y]): BiMap[X, Y] = {
      fromPairs(map.toVector)
    }
  }

  def error(msg: String): Unit = {
    System.err.println(Console.RED + msg + Console.RED)
  }

  /**
    * A wrapper around a primitive that enables passing a mutable variable by reference.
    * @param value the flag value
    */
  case class MutableHolder[T](var value: T)
}
