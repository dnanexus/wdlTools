package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Spacing.Spacing
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.Comment
import wdlTools.util.Util.MutableHolder

import scala.collection.mutable

class LineFormatter(inlineComments: Map[Position, Vector[Comment]],
                    indenting: Indenting = Indenting.IfNotIndented,
                    indentStep: Int = 2,
                    initialIndentSteps: Int = 0,
                    indentation: String = " ",
                    wrapping: Wrapping = Wrapping.AsNeeded,
                    maxLineWidth: Int = 100,
                    private val lines: mutable.Buffer[String],
                    private val currentLine: mutable.StringBuilder,
                    private val currentLineComments: mutable.Buffer[String],
                    private var currentIndentSteps: Int = 0,
                    private var currentSpacing: MutableHolder[Spacing] =
                      MutableHolder[Spacing](Spacing.Always),
                    private val lineBegun: MutableHolder[Boolean] = MutableHolder[Boolean](false)) {

  private val commentStart = "^#+".r
  private val whitespace = "[ \t\n\r]+".r

  /**
    * Derive a new LineFormatter with the current state modified by the specified parameters.
    * @param increaseIndent whether to incerase the indent by one step
    * @param newIndenting new value for `indenting`
    * @param newSpacing new value for `spacing`
    * @param newWrapping new value for `wrapping`
    * @return
    */
  def derive(increaseIndent: Boolean = false,
             newIndenting: Indenting = indenting,
             newSpacing: Spacing = currentSpacing.value,
             newWrapping: Wrapping = wrapping): LineFormatter = {
    val newInitialIndentSteps = initialIndentSteps + (if (increaseIndent) 1 else 0)
    val newCurrentIndentSteps = if (increaseIndent && newInitialIndentSteps > currentIndentSteps) {
      newInitialIndentSteps
    } else {
      currentIndentSteps
    }
    new LineFormatter(inlineComments,
                      newIndenting,
                      indentStep,
                      newInitialIndentSteps,
                      indentation,
                      newWrapping,
                      maxLineWidth,
                      lines,
                      currentLine,
                      currentLineComments,
                      newCurrentIndentSteps,
                      MutableHolder[Spacing](newSpacing),
                      lineBegun)
  }

  def isLineBegun: Boolean = lineBegun.value

  def atLineStart: Boolean = {
    currentLine.length <= (currentIndentSteps * indentStep)
  }

  def currentIndent: String = indentation * (currentIndentSteps * indentStep)

  def lengthRemaining: Int = {
    if (atLineStart) {
      maxLineWidth
    } else {
      maxLineWidth - currentLine.length
    }
  }

  def emptyLine(): Unit = {
    require(!isLineBegun)
    lines.append("")
  }

  def beginLine(): Unit = {
    require(!isLineBegun)
    currentLine.append(currentIndent)
    lineBegun.value = true
  }

  private def dent(indenting: Indenting): Unit = {
    indenting match {
      case Indenting.Always =>
        currentIndentSteps += 1
      case Indenting.IfNotIndented if currentIndentSteps == initialIndentSteps =>
        currentIndentSteps += 1
      case Indenting.Dedent if currentIndentSteps > initialIndentSteps =>
        currentIndentSteps -= 1
      case Indenting.Reset =>
        currentIndentSteps = initialIndentSteps
      case Indenting.Never =>
        currentIndentSteps = 0
      case _ => Unit
    }
  }

  def endLine(continue: Boolean = false): Unit = {
    require(isLineBegun)
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
      if (continue) {
        dent(indenting)
      } else {
        dent(Indenting.Reset)
      }
    }
    currentLine.clear()
    lineBegun.value = false
    if (currentSpacing.value == Spacing.AfterNext) {
      currentSpacing.value = Spacing.Always
    }
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
    require(!isLineBegun)

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
    require(isLineBegun)

    if (inlineComments.contains(span)) {
      val trimmedComments = trimComments(inlineComments(span))
      currentLineComments.appendAll(trimmedComments.map(_._1))
    }

    if (wrapping == Wrapping.Always) {
      endLine(continue = true)
      beginLine()
    } else {
      val addSpace = currentLine.nonEmpty &&
        currentSpacing.value == Spacing.Always &&
        !(currentLine.last.isWhitespace || currentLine.last == indentation.last)
      if (wrapping != Wrapping.Never && lengthRemaining < span.length + (if (addSpace) 1 else 0)) {
        endLine(continue = true)
        beginLine()
      } else if (addSpace) {
        currentLine.append(" ")
      }
    }

    span match {
      case c: Composite => c.formatContents(this)
      case a: Atom =>
        currentLine.append(a.toString)
        if (currentSpacing.value == Spacing.AfterNext) {
          currentSpacing.value = Spacing.Always
        }
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
            indentStep: Int = 2,
            initialIndentSteps: Int = 0,
            indentation: String = " ",
            wrapping: Wrapping = Wrapping.AsNeeded,
            maxLineWidth: Int = 100): LineFormatter = {
    val lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty
    val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
    val currentLineComments: mutable.Buffer[String] = mutable.ArrayBuffer.empty
    new LineFormatter(inlineComments,
                      indenting,
                      indentStep,
                      initialIndentSteps,
                      indentation,
                      wrapping,
                      maxLineWidth,
                      lines,
                      currentLine,
                      currentLineComments)
  }
}
