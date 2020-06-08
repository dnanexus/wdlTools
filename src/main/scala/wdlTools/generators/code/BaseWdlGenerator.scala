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
    def formatContents(lineGenerator: LineGenerator): Unit
  }

  class LineGenerator(
      indenting: Indenting = Indenting.IfNotIndented,
      indentStep: Int = 2,
      initialIndentSteps: Int = 0,
      indentation: String = " ",
      wrapping: Wrapping = Wrapping.AsNeeded,
      maxLineWidth: Int = 100,
      protected override val lines: mutable.Buffer[String],
      protected override val currentLine: mutable.StringBuilder,
      private val curIndentSteps: Int = 0,
      private val curSpacing: Spacing = Spacing.On,
      protected override val lineBegun: MutableHolder[Boolean] = MutableHolder[Boolean](false),
      protected override val skipNextSpace: MutableHolder[Boolean] = MutableHolder[Boolean](false)
  ) extends BaseLineFormatter(
          indenting,
          indentStep,
          initialIndentSteps,
          indentation,
          wrapping,
          maxLineWidth,
          curIndentSteps,
          curSpacing
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
               newIndenting: Indenting = indenting,
               newSpacing: Spacing = currentSpacing,
               newWrapping: Wrapping = wrapping): LineGenerator = {
      val (newInitialIndentSteps, newCurrentIndentSteps) = deriveIndent(increaseIndent)
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

    def append(sized: Sized): Unit = {
      require(isLineBegun)
      beforeAppend(sized)
      sized match {
        case c: Composite => c.formatContents(this)
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
  }
}
