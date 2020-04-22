package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.{Comment, TextSource}

import scala.collection.mutable

case class LineFormatter(inlineComments: Map[TextSource, Vector[Comment]],
                         defaultIndenting: Indenting = Indenting.IfNotIndented,
                         initialIndent: String = "",
                         indentation: String = " ",
                         indentStep: Int = 2,
                         defaultSpacing: String = " ",
                         maxLineWidth: Int = 100,
                         lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty) {
  private val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
  private val indent: mutable.StringBuilder = new mutable.StringBuilder(initialIndent)
  private val currentLineComments: mutable.Buffer[String] = mutable.ArrayBuffer.empty
  private val commentStart = "^#+".r
  private val whitespace = "[ \t\n\r]+".r

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

  def appendInlineComment(text: String): Unit = {
    currentLineComments.append(text)
  }

  def indented(indenting: Indenting = defaultIndenting): LineFormatter = {
    LineFormatter(
        inlineComments,
        defaultIndenting = indenting,
        initialIndent = initialIndent + (indentation * indentStep),
        indentation = indentation,
        indentStep = indentStep,
        maxLineWidth = maxLineWidth,
        lines = lines
    )
  }

  def preformatted(): LineFormatter = {
    LineFormatter(
        inlineComments,
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

  def emptyLine(): Unit = {
    lines.append("")
  }

  def beginLine(): Unit = {
    require(atLineStart)
    if (currentLine.isEmpty) {
      currentLine.append(indent)
    }
  }

  private def dent(indenting: Indenting = defaultIndenting): Unit = {
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

  def endLine(wrap: Boolean = false, indenting: Indenting = defaultIndenting): Unit = {
    if (currentLineComments.nonEmpty) {
      if (!atLineStart) {
        currentLine.append("  ")
      }
      currentLine.append(Symbols.Comment)
      currentLine.append(" ")
      currentLine.append(currentLineComments.mkString(" "))
      currentLineComments.clear()
    }
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

  def appendLineComments(comments: Vector[Comment]): Unit = {
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
          currentLine.append(Symbols.PreformattedComment)
          currentLine.append(" ")
          currentLine.append(trimmed)
          endLine()
        } else {
          if (atLineStart) {
            currentLine.append(Symbols.Comment)
          }
          if (lengthRemaining >= trimmed.length + 1) {
            currentLine.append(" ")
            currentLine.append(trimmed)
          } else {
            whitespace.split(trimmed).foreach { token =>
              // we let the line run over for a single token that is longer than the max line length
              // (i.e. we don't try to hyphenate)
              if (!atLineStart && lengthRemaining < token.length + 1) {
                endLine()
                beginLine()
                currentLine.append(Symbols.Comment)
              }
              currentLine.append(" ")
              currentLine.append(token)
            }
          }
        }
        prevLine = curLine
        preformatted = curPreformatted
    }

    endLine()
  }

  private def buildSubstring(
      chunks: Seq[Chunk],
      builder: mutable.StringBuilder = new mutable.StringBuilder(maxLineWidth),
      spacing: String = defaultSpacing
  ): StringBuilder = {
    chunks.foreach { chunk =>
      if (builder.nonEmpty && !(builder.last.isWhitespace || builder.last == indentation.last)) {
        builder.append(spacing)
      }
      builder.append(chunk.toString)
      if (inlineComments.contains(chunk.textSource)) {
        val trimmedComments = trimComments(inlineComments(chunk.textSource))
        currentLineComments.appendAll(trimmedComments.map(_._1))
      }
    }
    builder
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
