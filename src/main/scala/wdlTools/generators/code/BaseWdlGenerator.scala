package wdlTools.generators.code

import wdlTools.generators.code.Indenting.Indenting
import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.util.Util.MutableHolder

import scala.collection.mutable

object BaseWdlGenerator {
  trait Composite extends Sized {

    /**
      * Format the contents of the composite. The `lineGenerator` passed to this method
      * must have `isLineBegun == true` on both entry and exit.
      *
      * @param lineGenerator the LineGenerator
      */
    def generateContents(lineGenerator: LineGenerator): Unit
  }

  class LineGenerator(
      indenting: Indenting = Indenting.IfNotIndented,
      indentStep: Int = 2,
      initialIndentSteps: Int = 0,
      indentation: String = " ",
      wrapping: Wrapping = Wrapping.AsNeeded,
      maxLineWidth: Int = 100,
      private val lines: mutable.Buffer[String],
      private val currentLine: mutable.StringBuilder,
      private var currentIndentSteps: Int = 0,
      private var currentSpacing: Spacing = Spacing.On,
      private val lineBegun: MutableHolder[Boolean] = MutableHolder[Boolean](false),
      private val skipNextSpace: MutableHolder[Boolean] = MutableHolder[Boolean](false)
  ) {

    /**
      * Derive a new LineFormatter with the current state modified by the specified parameters.
      *
      * @param increaseIndent whether to incerase the indent by one step
      * @param newIndenting new value for `indenting`
      * @param newSpacing new value for `spacing`
      * @param newWrapping new value for `wrapping`
      * @return
      */
    def derive(increaseIndent: Boolean = false,
               continuing: Boolean = false,
               newIndenting: Indenting = indenting,
               newSpacing: Spacing = currentSpacing,
               newWrapping: Wrapping = wrapping): LineGenerator = {
      val indentSteps = if (continuing) currentIndentSteps else initialIndentSteps
      val newInitialIndentSteps = indentSteps + (if (increaseIndent) 1 else 0)
      val newCurrentIndentSteps =
        if (increaseIndent && newInitialIndentSteps > currentIndentSteps) {
          newInitialIndentSteps
        } else {
          currentIndentSteps
        }
      new LineGenerator(newIndenting,
                        indentStep,
                        newInitialIndentSteps,
                        indentation,
                        newWrapping,
                        maxLineWidth,
                        lines,
                        currentLine,
                        newCurrentIndentSteps,
                        newSpacing,
                        lineBegun,
                        skipNextSpace)
    }

    def isLineBegun: Boolean = lineBegun.value

    def atLineStart: Boolean = {
      currentLine.length <= (currentIndentSteps * indentStep)
    }

    def getIndent(changeSteps: Int = 0): String = {
      indentation * ((currentIndentSteps + changeSteps) * indentStep)
    }

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
      currentLine.append(getIndent())
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

    def append(sized: Sized): Unit = {
      require(isLineBegun)
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
      sized match {
        case c: Composite => c.generateContents(this)
        case a =>
          currentLine.append(a.toString)
          if (skipNextSpace.value) {
            skipNextSpace.value = false
          }
      }
    }

    def appendAll(spans: Vector[Sized]): Unit = {
      spans.foreach(append)
    }

    // TODO: these two methods are a hack - they are currently needed to handle the case of
    //  printing a prefix followed by any number of spans followed by a suffix, and suppress
    //  the space after the prefix and before the suffix. Ideally, this would be handled by
    //  `append` using a different `Spacing` value.

    def appendPrefix(prefix: Sized): Unit = {
      append(prefix)
      skipNextSpace.value = true
    }

    def appendSuffix(suffix: Sized): Unit = {
      skipNextSpace.value = true
      append(suffix)
    }

    def toVector: Vector[String] = {
      lines.toVector
    }
  }

  object LineGenerator {
    def apply(indenting: Indenting = Indenting.IfNotIndented,
              indentStep: Int = 2,
              initialIndentSteps: Int = 0,
              indentation: String = " ",
              wrapping: Wrapping = Wrapping.AsNeeded,
              maxLineWidth: Int = 100): LineGenerator = {
      val lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty
      val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
      new LineGenerator(indenting,
                        indentStep,
                        initialIndentSteps,
                        indentation,
                        wrapping,
                        maxLineWidth,
                        lines,
                        currentLine)
    }
  }
}
