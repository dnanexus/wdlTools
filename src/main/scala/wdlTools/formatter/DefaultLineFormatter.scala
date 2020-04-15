package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.{Comment, CommentEmpty, CommentLine, CommentPreformatted, CommentCompound}

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

  private lazy val whitespace = "[ \t\n\r]+".r

  override def appendComment(comment: Comment): Unit = {
    require(atLineStart)
    comment match {
      case CommentEmpty() =>
        beginLine()
        appendChunk(Token.Comment)
        endLine()
      case CommentLine(text) =>
        beginLine()
        appendChunk(Token.Comment)
        whitespace.split(text).foreach { token =>
          // we let the line run over for a single token that is longer than the max line length
          // (i.e. we don't try to hyphenate)
          if (!atLineStart && lengthRemaining < token.length + 1) {
            endLine()
            beginLine()
            appendChunk(Token.Comment)
          }
          currentLine.append(" ")
          currentLine.append(token)
        }
        endLine()
      case CommentPreformatted(preLines) =>
        preLines.foreach { line =>
          beginLine()
          appendAll(Vector[Chunk](Token.PreformattedComment, StringLiteral(line)), Wrapping.Never)
          endLine()
        }
      case CommentCompound(comments) => comments.foreach(appendComment)
    }
  }

  def appendString(value: String): Unit = {
    currentLine.append(value)
  }

  def appendChunk(chunk: Chunk, spacing: String = defaultSpacing): Unit = {
    buildSubstring(Vector(chunk), currentLine, spacing)
  }

  def appendAll(chunks: Seq[Chunk],
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

  def toSeq: Seq[String] = {
    lines.toVector
  }
}
