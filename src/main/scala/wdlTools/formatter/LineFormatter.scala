package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping

import scala.collection.mutable

case class LineFormatter(defaultIndenting: Indenting = Indenting.IfNotIndented,
                         initialIndent: String = "",
                         indentation: String = " ",
                         indentStep: Int = 2,
                         maxLineWidth: Int = 100,
                         lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty) {
  private val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
  private val indent: mutable.StringBuilder = new mutable.StringBuilder("")

  def indented(indenting: Indenting = defaultIndenting): LineFormatter = {
    LineFormatter(defaultIndenting = indenting,
                  initialIndent = initialIndent + (indentation * indentStep),
                  lines = lines)
  }

  def atLineStart: Boolean = {
    currentLine.length == indent.length
  }

  def lengthRemaining: Int = {
    maxLineWidth - currentLine.length
  }

  def maybeIndent(indenting: Indenting = defaultIndenting): Unit = {
    if (indenting == Indenting.Always || (
            indenting == Indenting.IfNotIndented && indent.length == initialIndent.length
        )) {
      indent.append(indentation * indentStep)
    }
  }

  def endLine(): Unit = {
    endLineUnlessEmpty(wrap = false)
    indent.clear()
    indent.append(initialIndent)
  }

  def endLineUnlessEmpty(wrap: Boolean, indenting: Indenting = defaultIndenting): Unit = {
    if (!atLineStart) {
      lines.append(currentLine.toString)
      currentLine.clear()
      if (wrap) {
        maybeIndent(indenting)
      }
    }
    if (indenting != Indenting.Dedent) {
      val indentLength = indent.length
      if (indentLength == indentStep) {
        indent.clear()
      } else if (indentLength > indentStep) {
        indent.delete(indentLength - indentStep, indentLength)
      }
    }
  }

  def buildSubstring(
      atoms: Seq[Atom],
      builder: mutable.StringBuilder = new mutable.StringBuilder(maxLineWidth)
  ): StringBuilder = {
    atoms.foreach { atom =>
      if (builder.nonEmpty && !builder.last.isWhitespace) {
        builder.append(" ")
      }
      builder.append(atom.toString)
    }
    builder
  }

  def append(value: String): Unit = {
    currentLine.append(value)
  }

  def append(atom: Atom): Unit = {
    buildSubstring(Vector(atom), currentLine)
  }

  def appendAll(atoms: Seq[Atom], wrapping: Wrapping = Wrapping.AsNeeded): Unit = {
    if (wrapping == Wrapping.Never) {
      buildSubstring(atoms, currentLine)
    } else {
      val substr = buildSubstring(atoms)
      if (wrapping == Wrapping.Always) {
        endLineUnlessEmpty(wrap = true)
      }
      val space = if (atLineStart) {
        ""
      } else {
        " "
      }
      if (wrapping != Wrapping.Never && lengthRemaining < space.length + substr.length) {
        atoms.foreach { atom =>
          atom.format(lineFormatter = this)
        }
      } else {
        currentLine.append(space)
        currentLine.append(substr)
      }
    }
  }
}
