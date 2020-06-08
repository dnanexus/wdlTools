package wdlTools.generators.code

import wdlTools.generators.code.Indenting.Indenting
import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.util.Util.MutableHolder

import scala.collection.mutable

abstract class BaseLineFormatter(
    indenting: Indenting = Indenting.IfNotIndented,
    indentStep: Int = 2,
    initialIndentSteps: Int = 0,
    indentation: String = " ",
    wrapping: Wrapping = Wrapping.AsNeeded,
    maxLineWidth: Int = 100,
    curIndentSteps: Int,
    curSpacing: Spacing
) {
  protected def lineBegun: MutableHolder[Boolean]
  protected def skipNextSpace: MutableHolder[Boolean]
  protected def currentLine: mutable.StringBuilder
  protected def lines: mutable.Buffer[String]

  protected var currentIndentSteps: Int = curIndentSteps
  protected var currentSpacing: Spacing = curSpacing

  protected def deriveIndent(increaseIndent: Boolean = false): (Int, Int) = {
    val newInitialIndentSteps = initialIndentSteps + (if (increaseIndent) 1 else 0)
    val newCurrentIndentSteps = if (increaseIndent && newInitialIndentSteps > currentIndentSteps) {
      newInitialIndentSteps
    } else {
      currentIndentSteps
    }
    (newInitialIndentSteps, newCurrentIndentSteps)
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
      case _ => ()
    }
  }

  def endLine(continue: Boolean = false): Unit = {
    require(isLineBegun)
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
    skipNextSpace.value = false
  }

  protected def beforeAppend(sized: Sized): Unit = {
    if (wrapping == Wrapping.Always) {
      endLine(continue = true)
      beginLine()
    } else {
      val addSpace = currentLine.nonEmpty &&
        currentSpacing == Spacing.On &&
        !skipNextSpace.value &&
        !currentLine.last.isWhitespace &&
        currentLine.last != indentation.last
      if (wrapping != Wrapping.Never && lengthRemaining < sized.length + (if (addSpace) 1 else 0)) {
        endLine(continue = true)
        beginLine()
      } else if (addSpace) {
        currentLine.append(" ")
      }
    }
  }

  def toVector: Vector[String] = {
    lines.toVector
  }
}
