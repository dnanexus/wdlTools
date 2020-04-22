package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.Comment

import scala.collection.mutable

case class DefaultLineFormatter(defaultIndenting: Indenting = Indenting.IfNotIndented,
                                initialIndent: String = "",
                                indentation: String = " ",
                                indentStep: Int = 2,
                                defaultSpacing: String = " ",
                                maxLineWidth: Int = 100,
                                lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty)
    extends LineFormatter(defaultIndenting, defaultSpacing) {
  private val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
  private val indent: mutable.StringBuilder = new mutable.StringBuilder(initialIndent)

  def indented(indenting: Indenting = defaultIndenting): LineFormatter = {
    DefaultLineFormatter(
        defaultIndenting = indenting,
        initialIndent = initialIndent + (indentation * indentStep),
        indentation = indentation,
        indentStep = indentStep,
        maxLineWidth = maxLineWidth,
        lines = lines
    )
  }

  def preformatted(): LineFormatter = {
    DefaultLineFormatter(
        defaultSpacing = "",
        maxLineWidth = maxLineWidth,
        lines = lines
    )
  }

  def atLineStart: Boolean = {
    currentLine.length <= indent.length
  }

  def lengthRemaining: Int = {
    if (atLineStart) {
      maxLineWidth
    } else {
      maxLineWidth - currentLine.length
    }
  }

  def dent(indenting: Indenting = defaultIndenting): Unit = {
    indenting match {
      case Indenting.Always => indent.append(indentation * indentStep)
      case Indenting.IfNotIndented if indent.length == initialIndent.length =>
        indent.append(indentation * indentStep)
      case Indenting.Dedent =>
        val indentLength = indent.length
        if (indentLength > initialIndent.length) {
          indent.delete(indentLength - indentStep, indentLength)
        }
      case Indenting.Reset =>
        indent.clear()
        indent.append(initialIndent)
      case _ => Unit
    }
  }

  def emptyLine(): Unit = {
    lines.append("")
  }

  def beginLine(): Unit = {
    require(atLineStart)
    if (currentLine.isEmpty) {
      currentLine.append(indent)
    }
  }

  def endLine(wrap: Boolean = false, indenting: Indenting = defaultIndenting): Unit = {
    if (!atLineStart) {
      lines.append(currentLine.toString)
      if (wrap) {
        dent(indenting)
      } else {
        dent(Indenting.Reset)
      }
    }
    currentLine.clear()
  }

  def buildSubstring(
      chunks: Seq[Chunk],
      builder: mutable.StringBuilder = new mutable.StringBuilder(maxLineWidth),
      spacing: String = defaultSpacing
  ): StringBuilder = {
    chunks.foreach { chunk =>
      if (builder.nonEmpty && !(builder.last.isWhitespace || builder.last == indentation.last)) {
        builder.append(spacing)
      }
      builder.append(chunk.toString)
    }
    builder
  }

  private lazy val commentStart = "^#+".r
  private lazy val whitespace = "[ \t\n\r]+".r

  private def trimComments(comments: Vector[Comment]): Vector[(String, Int, Boolean)] = {
    comments.map { comment =>
      val text = comment.value.trim
      val hashes = commentStart.findFirstIn(text)
      if (hashes.isEmpty) {
        throw new Exception("Expected comment to start with '#'")
      }
      (text.substring(hashes.get.length),
       comment.text.line,
       hashes.get.startsWith(Symbols.PreformattedComment))
    }
  }

  override def appendLineComments(comments: Vector[Comment]): Unit = {
    require(atLineStart)

    var prevLine = 0
    var preformatted = false

    trimComments(comments).foreach {
      case (trimmed, curLine, curPreformatted) =>
        if (prevLine > 0 && curLine > prevLine + 1) {
          endLine()
          emptyLine()
        } else if (!preformatted && curPreformatted) {
          endLine()
        }
        if (curPreformatted) {
          beginLine()
          appendString(Symbols.PreformattedComment)
          appendString(" ")
          appendString(trimmed)
          endLine()
        } else {
          if (atLineStart) {
            appendString(Symbols.Comment)
          }
          if (lengthRemaining >= trimmed.length + 1) {
            appendString(" ")
            appendString(trimmed)
          } else {
            whitespace.split(trimmed).foreach { token =>
              // we let the line run over for a single token that is longer than the max line length
              // (i.e. we don't try to hyphenate)
              if (!atLineStart && lengthRemaining < token.length + 1) {
                endLine()
                beginLine()
                appendString(Symbols.Comment)
              }
              appendString(" ")
              appendString(token)
            }
          }
        }
        prevLine = curLine
        preformatted = curPreformatted
    }
  }

  override def appendInlineComment(comment: String): Unit = {
    if (!atLineStart) {
      appendString("  ")
    }
    appendString(Symbols.Comment)
    appendString(" ")
    appendString(comment)
  }

  override def appendInlineComment(comments: Vector[Comment]): Unit = {
    val trimmedComments = trimComments(comments)
    val text = if (trimmedComments.size > 1) {
      trimmedComments.map(_._1).mkString(" ")
    } else {
      trimmedComments.head._1
    }
    appendInlineComment(text)
  }

  def appendString(value: String): Unit = {
    currentLine.append(value)
  }

  def appendChunk(chunk: Chunk, spacing: String = defaultSpacing): Unit = {
    buildSubstring(Vector(chunk), currentLine, spacing)
  }

  def appendAll(chunks: Vector[Chunk],
                wrapping: Wrapping = Wrapping.AsNeeded,
                spacing: String = defaultSpacing): Unit = {
    if (wrapping == Wrapping.Never) {
      buildSubstring(chunks, currentLine)
    } else {
      val substr = buildSubstring(chunks, spacing = spacing)
      if (wrapping == Wrapping.Always) {
        endLine(wrap = true)
      }
      val space = if (atLineStart) {
        ""
      } else {
        spacing
      }
      val wrap = if (wrapping == Wrapping.Never) {
        false
      } else if (lengthRemaining < space.length + substr.length) {
        true
      } else {
        chunks.exists(_.wrapAll)
      }
      if (wrap) {
        chunks.foreach { chunk =>
          chunk.format(lineFormatter = this)
        }
      } else {
        currentLine.append(space)
        currentLine.append(substr)
      }
    }
  }

  def toVector: Vector[String] = {
    lines.toVector
  }
}
