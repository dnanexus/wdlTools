package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.Comment

import scala.collection.mutable

class LineFormatter(inlineComments: Map[Position, Vector[Comment]],
                    indenting: Indenting = Indenting.IfNotIndented,
                    initialIndent: String = "",
                    indentation: String = " ",
                    indentStep: Int = 2,
                    spacing: String = " ",
                    wrapping: Wrapping = Wrapping.AsNeeded,
                    maxLineWidth: Int = 100,
                    private val lines: mutable.Buffer[String],
                    private val currentLine: mutable.StringBuilder,
                    private val currentIndent: mutable.StringBuilder,
                    private val currentLineComments: mutable.Buffer[String],
                    private var lineBegun: Boolean = false) {

  private val commentStart = "^#+".r
  private val whitespace = "[ \t\n\r]+".r

  def derive(indent: Boolean = false,
             unspaced: Boolean = false,
             newWrapping: Wrapping = wrapping): LineFormatter = {
    val newIndent = if (indent) {
      initialIndent + (indentation * indentStep)
    } else {
      initialIndent
    }
    val newSpacing = if (unspaced) {
      ""
    } else {
      spacing
    }
    new LineFormatter(inlineComments,
                      indenting,
                      newIndent,
                      indentation,
                      indentStep,
                      newSpacing,
                      newWrapping,
                      maxLineWidth,
                      lines,
                      currentLine,
                      currentIndent,
                      currentLineComments,
                      lineBegun)
  }

  def isLineBegun: Boolean = lineBegun

  def atLineStart: Boolean = {
    currentLine.length <= currentIndent.length
  }

  def lengthRemaining: Int = {
    if (atLineStart) {
      maxLineWidth
    } else {
      maxLineWidth - currentLine.length
    }
  }

  def emptyLine(): Unit = {
    require(!lineBegun)
    lines.append("")
  }

  def beginLine(): Unit = {
    require(!lineBegun)
    currentLine.append(currentIndent)
    lineBegun = true
  }

  private def dent(indenting: Indenting): Unit = {
    indenting match {
      case Indenting.Always => currentIndent.append(indentation * indentStep)
      case Indenting.IfNotIndented if currentIndent.length == initialIndent.length =>
        currentIndent.append(indentation * indentStep)
      case Indenting.Dedent =>
        val indentLength = currentIndent.length
        if (indentLength > initialIndent.length) {
          currentIndent.delete(indentLength - indentStep, indentLength)
        }
      case Indenting.Reset =>
        currentIndent.clear()
        currentIndent.append(initialIndent)
      case _ => Unit
    }
  }

  def endLine(continue: Boolean = false): Unit = {
    require(lineBegun)
    if (currentLineComments.nonEmpty) {
      if (!atLineStart) {
        currentLine.append("  ")
      }
      currentLine.append(Symbols.Comment)
      currentLine.append(" ")
      currentLine.append(currentLineComments.mkString(" "))
      currentLineComments.clear()
    }
    println(s"endLine ${atLineStart} ${currentLine.toString}")
    if (!atLineStart) {
      lines.append(currentLine.toString)
      if (continue) {
        dent(indenting)
      } else {
        dent(Indenting.Reset)
      }
    }
    currentLine.clear()
    lineBegun = false
  }

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

  /**
    * Add one or more full-line comments.
    *
    * Unlike the append* methods, this method requires `isLineBegun == false` on
    * entry and exit.
    *
    * @param comments the comments to add
    */
  def addLineComments(comments: Vector[Comment]): Unit = {
    require(!lineBegun)

    beginLine()

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
          currentLine.append(Symbols.PreformattedComment)
          currentLine.append(" ")
          currentLine.append(trimmed)
          endLine()
          beginLine()
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

  def append(span: Span): Unit = {
    require(lineBegun)

    if (inlineComments.contains(span)) {
      val trimmedComments = trimComments(inlineComments(span))
      currentLineComments.appendAll(trimmedComments.map(_._1))
    }

    if (wrapping == Wrapping.Always) {
      endLine(continue = true)
      beginLine()
    } else {
      val space =
        if (currentLine.nonEmpty && !(currentLine.last.isWhitespace || currentLine.last == indentation.last)) {
          spacing.length
        } else {
          0
        }

      if (wrapping == Wrapping.AsNeeded && lengthRemaining < space + span.length) {
        endLine(continue = true)
        beginLine()
      } else if (space > 0) {
        currentLine.append(spacing)
      }
    }

    println(s"span class ${span.getClass}")

    span match {
      case c: Composite => c.formatContents(this)
      case a: Atom      => currentLine.append(a.toString)
      case other =>
        throw new Exception(s"Span ${other} must implement either Atom or Delegate trait")
    }
  }

  def appendAll(spans: Vector[Span]): Unit = {
    spans.foreach(append)
  }

  def toVector: Vector[String] = {
    lines.toVector
  }
}

object LineFormatter {
  def apply(inlineComments: Map[Position, Vector[Comment]],
            indenting: Indenting = Indenting.IfNotIndented,
            initialIndent: String = "",
            indentation: String = " ",
            indentStep: Int = 2,
            spacing: String = " ",
            wrapping: Wrapping = Wrapping.AsNeeded,
            maxLineWidth: Int = 100): LineFormatter = {
    val lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty
    val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
    val indent: mutable.StringBuilder = new mutable.StringBuilder(initialIndent)
    val currentLineComments: mutable.Buffer[String] = mutable.ArrayBuffer.empty
    new LineFormatter(inlineComments,
                      indenting,
                      initialIndent,
                      indentation,
                      indentStep,
                      spacing,
                      wrapping,
                      maxLineWidth,
                      lines,
                      currentLine,
                      indent,
                      currentLineComments)
  }
}
