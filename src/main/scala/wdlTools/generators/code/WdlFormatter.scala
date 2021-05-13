package wdlTools.generators.code

import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.generators.code.Indenting.Indenting
import wdlTools.generators.code.WdlFormatter._
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Comment, CommentMap, Operator, Parsers, SourceLocation, WdlVersion}
import dx.util.{FileNode, FileSourceResolver, Logger}

import scala.collection.{BufferedIterator, mutable}

object WdlFormatter {

  /**
    * An element that (potentially) spans multiple source lines.
    */
  trait Multiline extends Ordered[Multiline] {
    def line: Int

    def endLine: Int

    lazy val lineRange: Range = line to endLine

    override def compare(that: Multiline): Int = {
      line - that.line match {
        case 0     => endLine - that.endLine
        case other => other
      }
    }
  }

  /**
    * An element that can be formatted by a Formatter.
    * Column positions are 1-based and end-exclusive
    */
  trait Span extends Sized with Multiline {

    /**
      * The first column in the span.
      */
    def column: Int

    /**
      * The last column in the span.
      */
    def endColumn: Int
  }

  object Span {
    // indicates the last token on a line
    val TERMINAL: Int = Int.MaxValue
  }

  /**
    * Marker trait for atomic Spans - those that format themselves via their
    * toString method. An atomic Span is always on a single source line (i.e.
    * `line` == `endLine`).
    */
  trait Atom extends Span {
    override def endLine: Int = line

    def toString: String
  }

  /**
    * A Span that contains other Spans and knows how to format itself.
    */
  trait Composite extends Span {

    /**
      * Format the contents of the composite. The `lineFormatter` passed to this method
      * must have `isLineBegun == true` on both entry and exit.
      *
      * @param lineFormatter the lineFormatter
      */
    def formatContents(lineFormatter: LineFormatter): Unit

    /**
      * Whether this Composite is a section, which may contain full-line comments.
      */
    def isSection: Boolean = false
  }

  class LineFormatter(
      comments: CommentMap,
      indenting: Indenting = Indenting.IfNotIndented,
      indentStep: Int = 2,
      initialIndentSteps: Int = 0,
      indentation: String = " ",
      wrapping: Wrapping = Wrapping.AsNeeded,
      maxLineWidth: Int = 100,
      private val lines: mutable.Buffer[String],
      private val currentLine: mutable.StringBuilder,
      private val currentLineComments: mutable.Map[Int, String] = mutable.HashMap.empty,
      private var currentIndentSteps: Int = 0,
      private var currentSpacing: Spacing = Spacing.On,
      private val lineBegun: MutableHolder[Boolean] = MutableHolder[Boolean](false),
      private val sections: mutable.Buffer[Multiline] = mutable.ArrayBuffer.empty,
      private val currentSourceLine: MutableHolder[Int] = MutableHolder[Int](0),
      private val skipNextSpace: MutableHolder[Boolean] = MutableHolder[Boolean](false)
  ) {

    private val commentStart = "^#+".r
    private val whitespace = "[ \t\n\r]+".r

    /**
      * Derive a new LineFormatter with the current state modified by the specified parameters.
      *
      * @param increaseIndent whether to incerase the indent by one step
      * @param newIndenting   new value for `indenting`
      * @param newSpacing     new value for `spacing`
      * @param newWrapping    new value for `wrapping`
      * @return
      */
    def derive(increaseIndent: Boolean = false,
               newIndentSteps: Option[Int] = None,
               newIndenting: Indenting = indenting,
               newSpacing: Spacing = currentSpacing,
               newWrapping: Wrapping = wrapping): LineFormatter = {
      val newInitialIndentSteps =
        newIndentSteps.getOrElse(initialIndentSteps + (if (increaseIndent) 1 else 0))
      val newCurrentIndentSteps = Math.max(currentIndentSteps, newInitialIndentSteps)
      new LineFormatter(comments,
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
                        newSpacing,
                        lineBegun,
                        sections,
                        currentSourceLine,
                        skipNextSpace)
    }

    def isLineBegun: Boolean = lineBegun.value

    def atLineStart: Boolean = {
      currentLine.length <= (currentIndentSteps * indentStep)
    }

    def getIndentSteps(changeSteps: Int = 0): Int = {
      currentIndentSteps + changeSteps
    }

    def getIndent(changeSteps: Int = 0): String = {
      indentation * (getIndentSteps(changeSteps) * indentStep)
    }

    def lengthRemaining: Int = {
      maxLineWidth - Math.max(currentLine.length, currentIndentSteps * indentStep)
    }

    def beginSection(section: Multiline): Unit = {
      sections.append(section)
      currentSourceLine.value = section.line
    }

    def endSection(section: Multiline): Unit = {
      require(sections.nonEmpty)
      val popSection = sections.last
      if (section != popSection) {
        throw new Exception(s"Ending the wrong section: ${section} != ${popSection}")
      }
      maybeAppendFullLineComments(popSection, isSectionEnd = true)
      sections.remove(sections.size - 1)
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
      if (currentLineComments.nonEmpty) {
        if (!atLineStart) {
          currentLine.append("  ")
        }
        currentLine.append(Symbols.Comment)
        currentLine.append(" ")
        currentLine.append(
            currentLineComments.toVector.sortWith((a, b) => a._1 < b._1).map(_._2).mkString(" ")
        )
        currentLineComments.clear()
      }
      if (!atLineStart) {
        // the line could have trailing whitespace, such as from a comment or
        // when a space was added prior to a line-wrap - trim it off
        lines.append(currentLine.toString.replaceAll("""(?m)[ \t]+$""", ""))
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

    private def trimComment(comment: Comment): (String, Int, Boolean) = {
      val text = comment.value.trim
      val hashes = commentStart.findFirstIn(text)
      if (hashes.isEmpty) {
        throw new Exception("Expected comment to start with '#'")
      }
      val preformatted = hashes.get.startsWith(Symbols.PreformattedComment)
      val rawText = text.substring(hashes.get.length)
      (if (preformatted) rawText else rawText.trim, comment.loc.line, preformatted)
    }

    /**
      * Append one or more full-line comments.
      *
      * @param ml           the Multiline before which comments should be added
      * @param isSectionEnd if true, comments are added between the previous source line and
      *                     the end of the section; otherwise comments are added between the previous source
      *                     line and the beginning of `ml`
      */
    private def maybeAppendFullLineComments(ml: Multiline, isSectionEnd: Boolean = false): Unit = {
      val beforeLine = if (isSectionEnd) ml.endLine else ml.line
      require(beforeLine >= currentSourceLine.value)
      require(beforeLine <= sections.last.endLine)

      val lineComments = comments.filterWithin((currentSourceLine.value + 1) until beforeLine)

      if (lineComments.nonEmpty) {
        val sortedComments = lineComments.toSortedVector
        val beforeDist = sortedComments.head.loc.line - currentSourceLine.value
        val afterDist = beforeLine - sortedComments.last.loc.endLine

        if (beforeDist > 1) {
          lines.append("")
        }

        var prevLine = 0
        var preformatted = false

        sortedComments.map(trimComment).foreach {
          case (trimmed, curLine, curPreformatted) =>
            if (prevLine > 0 && curLine > prevLine + 1) {
              endLine()
              emptyLine()
              beginLine()
            } else if (!preformatted && curPreformatted) {
              endLine()
              beginLine()
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
                  // we let the line run over for a single token that is longer than
                  // the max line length (i.e. we don't try to hyphenate)
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

        if (afterDist > 1) {
          emptyLine()
        }

        beginLine()
      }

      currentSourceLine.value = ml match {
        case c: Composite if c.isSection && !isSectionEnd => ml.line
        case _                                            => ml.endLine
      }
    }

    /**
      * Add to `currentLineComments` any end-of-line comments associated with any of
      * `span`'s source lines.
      */
    private def maybeAddInlineComments(atom: Atom): Unit = {
      val range = atom match {
        case m: Multiline => m.lineRange
        case s            => s.line to s.line
      }
      currentLineComments ++= comments
        .filterWithin(range)
        .toSortedVector
        .filter(comment => !currentLineComments.contains(comment.loc.line))
        .map(comment => comment.loc.line -> trimComment(comment)._1)
    }

    def addInlineComment(line: Int, text: String): Unit = {
      require(!currentLineComments.contains(line))
      currentLineComments(line) = text
    }

    def append(span: Span, continue: Boolean = true): Unit = {
      require(isLineBegun)

      if (atLineStart && sections.nonEmpty) {
        maybeAppendFullLineComments(span)
      }

      if (wrapping == Wrapping.Always) {
        endLine(continue = continue)
        beginLine()
      } else {
        val addSpace = currentLine.nonEmpty &&
          currentSpacing == Spacing.On &&
          !skipNextSpace.value &&
          !currentLine.last.isWhitespace &&
          currentLine.last != indentation.last
        if (wrapping != Wrapping.Never && lengthRemaining < (
                span.firstLineLength + (if (addSpace) 1 else 0)
            )) {
          endLine(continue = continue)
          beginLine()
        } else if (addSpace) {
          currentLine.append(" ")
        }
      }

      span match {
        case c: Composite =>
          if (c.isSection) {
            beginSection(c)
          }
          c.formatContents(this)
          if (c.isSection) {
            endSection(c)
          }
        case a: Atom =>
          currentLine.append(a.toString)
          if (skipNextSpace.value) {
            skipNextSpace.value = false
          }
          if (a.line > currentSourceLine.value) {
            currentSourceLine.value = a.line
          }
          maybeAddInlineComments(a)
        case other =>
          throw new Exception(s"Span ${other} must implement either Atom or Delegate trait")
      }
    }

    def appendAll(spans: Vector[Span], continue: Boolean = true): Unit = {
      spans.foreach(span => append(span, continue))
    }

    // TODO: these two methods are a hack - they are currently needed to handle the case of
    //  printing a prefix followed by any number of spans followed by a suffix, and suppress
    //  the space after the prefix and before the suffix. Ideally, this would be handled by
    //  `append` using a different `Spacing` value.

    def appendPrefix(prefix: Span): Unit = {
      append(prefix)
      skipNextSpace.value = true
    }

    def appendSuffix(suffix: Span): Unit = {
      skipNextSpace.value = true
      append(suffix)
    }

    def toVector: Vector[String] = {
      lines.toVector
    }
  }

  object LineFormatter {
    def apply(comments: CommentMap,
              indenting: Indenting = Indenting.IfNotIndented,
              indentStep: Int = 2,
              initialIndentSteps: Int = 0,
              indentation: String = " ",
              wrapping: Wrapping = Wrapping.AsNeeded,
              maxLineWidth: Int = 100): LineFormatter = {
      val lines: mutable.Buffer[String] = mutable.ArrayBuffer.empty
      val currentLine: mutable.StringBuilder = new StringBuilder(maxLineWidth)
      new LineFormatter(comments,
                        indenting,
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

case class WdlFormatter(targetVersion: Option[WdlVersion] = None,
                        followImports: Boolean = false,
                        fileResolver: FileSourceResolver = FileSourceResolver.get,
                        logger: Logger = Logger.get) {
  if (targetVersion.exists(_ < WdlVersion.V1)) {
    throw new Exception(s"WDL version ${targetVersion.get} is not supported")
  }

  private case class Literal(value: Any,
                             quoting: Boolean = false,
                             override val line: Int,
                             columns: (Option[Int], Option[Int]) = (None, None))
      extends Atom {

    override lazy val column: Int = {
      columns match {
        case (Some(start), _) => start
        case (_, Some(end))   => end - length
        case _                => Span.TERMINAL
      }
    }

    /**
      * The last column in the span - position is 1-based and end-exclusive.
      */
    override def endColumn: Int = {
      columns match {
        case (_, Some(end))   => end
        case (Some(start), _) => start + length
        case _                => Span.TERMINAL
      }
    }

    override lazy val length: Int = toString.length

    override lazy val toString: String = {
      if (quoting) {
        s"${'"'}${value}${'"'}"
      } else {
        value.toString
      }
    }
  }

  private object Literal {
    def fromStart(value: Any, loc: SourceLocation, quoting: Boolean = false): Literal = {
      Literal(value, quoting, loc.line, (Some(loc.col), None))
    }

    def fromStartPosition(value: Any,
                          line: Int,
                          column: Int = 1,
                          quoting: Boolean = false): Literal = {
      Literal(value, quoting, line, (Some(column), None))
    }

    def fromEnd(value: Any, loc: SourceLocation, quoted: Boolean = false): Literal = {
      Literal(value, quoted, loc.endLine, (None, Some(loc.endCol)))
    }

    def fromEndPosition(value: Any,
                        line: Int,
                        column: Int = Span.TERMINAL,
                        quoted: Boolean = false): Literal = {
      Literal(value, quoted, line, (None, Some(column)))
    }

    def fromPrev(value: Any, prev: Span, quoting: Boolean = false): Literal = {
      Literal(value, quoting, prev.endLine, (Some(prev.endColumn), None))
    }

    def fromNext(value: Any, next: Span, quoting: Boolean = false): Literal = {
      Literal(value, quoting, next.line, (None, Some(next.column)))
    }

    def between(value: String,
                prev: Span,
                next: Span,
                quoting: Boolean = false,
                preferPrev: Boolean = false): Literal = {
      if (prev.line == next.line) {
        require(prev.endColumn < next.column)
        Literal.fromPrev(value, prev, quoting)
      } else if (preferPrev) {
        Literal.fromPrev(value, prev, quoting)
      } else {
        Literal.fromNext(value, next, quoting)
      }
    }

    def chainFromStart(values: Vector[Any], start: SourceLocation): Vector[Literal] = {
      var prev = Literal.fromStart(values.head, start)
      Vector(prev) ++ values.tail.map { v =>
        val next = Literal.fromPrev(v, prev)
        prev = next
        next
      }
    }

    def chainFromPrev(values: Vector[Any], prev: Span): Vector[Literal] = {
      var p: Span = prev
      values.map { v =>
        val next = Literal.fromPrev(v, prev)
        p = next
        next
      }
    }
  }

  private case class SpanSequence(spans: Vector[Span],
                                  wrapping: Wrapping = Wrapping.Never,
                                  spacing: Spacing = Spacing.Off,
                                  continue: Boolean = true)
      extends Composite {
    require(spans.nonEmpty)

    override lazy val length: Int = spans.map(_.length).sum + (
        if (spacing == Spacing.On) spans.length else 0
    )

    override lazy val firstLineLength: Int = {
      if (wrapping == Wrapping.Never || wrapping == Wrapping.AllOrNone) {
        length
      } else {
        spans.head.firstLineLength
      }
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      val contentFormatter = if (wrapping == Wrapping.AllOrNone) {
        // inherit the lineFormatter's wrapping
        lineFormatter.derive(newSpacing = spacing)
      } else {
        lineFormatter.derive(newSpacing = spacing, newWrapping = wrapping)
      }
      contentFormatter.appendAll(spans, continue)
    }

    override def line: Int = spans.head.line

    override def endLine: Int = spans.last.endLine

    override def column: Int = spans.head.column

    override def endColumn: Int = spans.last.endColumn

    /**
      * Whether this Composite is a section, which may contain full-line comments.
      */
    override lazy val isSection: Boolean = {
      spans.exists {
        case c: Composite => c.isSection
        case _            => false
      }
    }
  }

  private abstract class Group(ends: Option[(Span, Span)] = None,
                               val wrapping: Wrapping = Wrapping.Never,
                               val spacing: Spacing = Spacing.On,
                               val continue: Boolean = false)
      extends Composite {

    private val endLengths: (Int, Int) =
      ends.map(e => (e._1.length, e._2.length)).getOrElse((0, 0))

    override lazy val length: Int = body
      .map(_.length)
      .getOrElse(0) + endLengths._1 + endLengths._2

    override lazy val firstLineLength: Int = {
      if (wrapping == Wrapping.Never || wrapping == Wrapping.AllOrNone || body.isEmpty) {
        length
      } else if (ends.isDefined) {
        ends.get._1.length
      } else {
        body.get.firstLineLength
      }
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      if (ends.isDefined) {
        val (prefix, suffix) = ends.get
        if (body.nonEmpty && (
                wrapping == Wrapping.Always || (wrapping != Wrapping.Never && length > lineFormatter.lengthRemaining)
            )) {
          val bodyIndent = lineFormatter.getIndentSteps(1)

          lineFormatter.append(prefix)
          lineFormatter.endLine(continue = continue)

          val effectiveWrapping = if (wrapping == Wrapping.AllOrNone) Wrapping.Always else wrapping
          val bodyFormatter = lineFormatter
            .derive(newIndentSteps = Some(bodyIndent),
                    newSpacing = Spacing.On,
                    newWrapping = effectiveWrapping)
          bodyFormatter.beginLine()
          bodyFormatter.append(body.get)
          bodyFormatter.endLine()

          lineFormatter.beginLine()
          lineFormatter.append(suffix)
        } else {
          val effectiveWrapping = if (wrapping == Wrapping.AllOrNone) Wrapping.Never else wrapping
          val adjacentFormatter =
            lineFormatter.derive(newSpacing = spacing, newWrapping = effectiveWrapping)
          adjacentFormatter.appendPrefix(prefix)
          if (body.nonEmpty) {
            adjacentFormatter.append(body.get)
          }
          adjacentFormatter.appendSuffix(suffix)
        }
      } else if (body.isDefined) {
        lineFormatter.derive(newSpacing = spacing, newWrapping = wrapping).append(body.get)
      }
    }

    def body: Option[Composite]
  }

  private abstract class Container(items: Vector[Span],
                                   delimiter: Option[String] = None,
                                   ends: Option[(Span, Span)] = None,
                                   override val wrapping: Wrapping = Wrapping.AsNeeded,
                                   continue: Boolean = true)
      extends Group(ends = ends, wrapping = wrapping, continue = continue) {

    override lazy val body: Option[Composite] = if (items.nonEmpty) {
      Some(
          SpanSequence(
              items.zipWithIndex.map {
                case (item, i) if i < items.size - 1 =>
                  if (delimiter.isDefined) {
                    val delimiterLiteral = Literal.fromPrev(delimiter.get, item)
                    SpanSequence(Vector(item, delimiterLiteral))
                  } else {
                    item
                  }
                case (item, _) => item
              },
              wrapping = wrapping,
              spacing = Spacing.On,
              continue = continue
          )
      )
    } else {
      None
    }

    override val isSection: Boolean = true
  }

  private trait Bounded {
    def bounds: SourceLocation
  }

  private trait BoundedComposite extends Composite with Bounded {
    override def line: Int = bounds.line

    override def endLine: Int = bounds.endLine

    override def column: Int = bounds.col

    override def endColumn: Int = bounds.endCol
  }

  private case class BoundedContainer(
      items: Vector[Span],
      ends: Option[(Span, Span)] = None,
      delimiter: Option[String] = None,
      override val bounds: SourceLocation,
      override val wrapping: Wrapping = Wrapping.Never,
      override val continue: Boolean = true
  ) extends Container(
          items,
          delimiter = delimiter,
          ends = ends,
          wrapping = wrapping,
          continue = continue
      )
      with BoundedComposite

  private case class KeyValue(key: Span,
                              value: Span,
                              delimiter: String = Symbols.KeyValueDelimiter,
                              override val bounds: SourceLocation,
                              override val isSection: Boolean = true)
      extends BoundedComposite {
    private val delimiterLiteral: Literal = Literal.fromPrev(delimiter, key)

    override def firstLineLength: Int = key.length + delimiterLiteral.length

    override def length: Int = firstLineLength + value.length + 1

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter
        .derive(newWrapping = Wrapping.Never, newSpacing = Spacing.On)
        .appendAll(Vector(SpanSequence(Vector(key, delimiterLiteral)), value))
    }
  }

  private object DataType {
    def buildDataType(name: String,
                      quantifiers: Vector[Span] = Vector.empty,
                      loc: SourceLocation,
                      inner1: Option[Span] = None,
                      inner2: Option[Span] = None): Span = {
      val nameLiteral: Literal = Literal.fromStart(name, loc)
      if (inner1.isDefined) {
        // making the assumption that the open token comes directly after the name
        val openLiteral = Literal.fromPrev(Symbols.TypeParamOpen, nameLiteral)
        val prefix = SpanSequence(Vector(nameLiteral, openLiteral))
        // making the assumption that the close token comes directly before the quantifier (if any)
        val suffix = if (quantifiers.nonEmpty) {
          SpanSequence(
              Vector(Literal.fromNext(Symbols.TypeParamClose, quantifiers.head)) ++ quantifiers
          )
        } else {
          Literal.fromEnd(Symbols.TypeParamClose, loc)
        }
        BoundedContainer(
            Vector(inner1, inner2).flatten,
            Some((prefix, suffix)),
            Some(Symbols.ArrayDelimiter),
            loc
        )
      } else if (quantifiers.nonEmpty) {
        SpanSequence(Vector(nameLiteral) ++ quantifiers)
      } else {
        nameLiteral
      }
    }

    private def isPrimitiveType(wdlType: Type): Boolean = {
      wdlType match {
        case _: TypeString    => true
        case _: TypeBoolean   => true
        case _: TypeInt       => true
        case _: TypeFloat     => true
        case _: TypeFile      => true
        case _: TypeDirectory => true
        case _                => false
      }
    }

    def fromWdlType(wdlType: Type, quantifiers: Vector[Span] = Vector.empty): Span = {
      wdlType match {
        case TypeOptional(inner, loc) =>
          fromWdlType(inner, quantifiers = Vector(Literal.fromEnd(Symbols.Optional, loc)))
        case TypeArray(inner, nonEmpty, loc) =>
          val quant: Vector[Span] = (nonEmpty, quantifiers) match {
            case (true, quant) if quant.nonEmpty =>
              Vector(Literal.fromNext(Symbols.NonEmpty, quantifiers.head)) ++ quant
            case (true, _)  => Vector(Literal.fromEnd(Symbols.NonEmpty, loc))
            case (false, _) => quantifiers
          }
          buildDataType(Symbols.ArrayType, quant, loc, Some(fromWdlType(inner)))
        case TypeMap(keyType, valueType, loc) if isPrimitiveType(keyType) =>
          buildDataType(Symbols.MapType,
                        quantifiers,
                        loc,
                        Some(fromWdlType(keyType)),
                        Some(fromWdlType(valueType)))
        case TypePair(left, right, loc) =>
          buildDataType(Symbols.PairType,
                        quantifiers,
                        loc,
                        Some(fromWdlType(left)),
                        Some(fromWdlType(right)))
        case TypeStruct(name, _, loc) => buildDataType(name, quantifiers, loc)
        case TypeObject(loc)          => buildDataType(Symbols.ObjectType, quantifiers, loc)
        case TypeString(loc)          => buildDataType(Symbols.StringType, quantifiers, loc)
        case TypeBoolean(loc)         => buildDataType(Symbols.BooleanType, quantifiers, loc)
        case TypeInt(loc)             => buildDataType(Symbols.IntType, quantifiers, loc)
        case TypeFloat(loc)           => buildDataType(Symbols.FloatType, quantifiers, loc)
        case TypeFile(loc)            => buildDataType(Symbols.FileType, quantifiers, loc)
        case TypeDirectory(loc) =>
          buildDataType(Symbols.DirectoryType, quantifiers, loc)
        case other => throw new Exception(s"Unrecognized type $other")
      }
    }
  }

  private case class Operation(oper: String,
                               lhs: Span,
                               rhs: Span,
                               grouped: Boolean = false,
                               inString: Boolean,
                               override val bounds: SourceLocation)
      extends Group(ends = if (grouped) {
        Some(Literal.fromStart(Symbols.GroupOpen, bounds),
             Literal.fromEnd(Symbols.GroupClose, bounds))
      } else {
        None
      }, wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded)
      with BoundedComposite {

    override lazy val body: Option[Composite] = {
      val operLiteral = Literal.between(oper, lhs, rhs)
      Some(SpanSequence(Vector(lhs, operLiteral, rhs), wrapping = wrapping, spacing = Spacing.On))
    }
  }

  private case class Placeholder(value: Span,
                                 open: String = Symbols.PlaceholderOpenDollar,
                                 close: String = Symbols.PlaceholderClose,
                                 options: Option[Vector[Span]] = None,
                                 inString: Boolean,
                                 override val bounds: SourceLocation)
      extends Group(
          ends = Some(Literal.fromStart(open, bounds), Literal.fromEnd(close, bounds)),
          wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded,
          spacing = if (inString) Spacing.Off else Spacing.On
      )
      with BoundedComposite {

    override lazy val body: Option[Composite] = Some(
        SpanSequence(
            options.getOrElse(Vector.empty) ++ Vector(value),
            wrapping = wrapping,
            spacing = Spacing.On
        )
    )
  }

  private case class CompoundString(spans: Vector[Span],
                                    quoting: Boolean,
                                    override val bounds: SourceLocation)
      extends BoundedComposite {
    override lazy val length: Int = spans
      .map(_.length)
      .sum + (if (quoting) 2 else 0)

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      val unspacedFormatter =
        lineFormatter.derive(newWrapping = Wrapping.Never, newSpacing = Spacing.Off)
      if (quoting) {
        unspacedFormatter.appendPrefix(
            Literal.fromStartPosition(Symbols.QuoteOpen, line, column)
        )
        unspacedFormatter.appendAll(spans)
        unspacedFormatter.appendSuffix(
            Literal.fromEndPosition(Symbols.QuoteClose, line, endColumn)
        )
      } else {
        unspacedFormatter.appendAll(spans)
      }
    }
  }

  private def buildExpression(
      expr: Expr,
      placeholderOpen: String = Symbols.PlaceholderOpenDollar,
      inString: Boolean = false,
      inCommand: Boolean = false,
      inPlaceholder: Boolean = false,
      inOperation: Boolean = false,
      parentOperation: Option[String] = None,
      stringModifier: Option[String => String] = None
  ): Span = {

    /*
     * Builds an expression that occurs nested within another expression. By default, passes
     * all the current parameter values to the nested call.
     * @param nestedExpression the nested Expr
     * @param placeholderOpen  override the current value of `placeholderOpen`
     * @param inString         override the current value of `inString`
     * @param inPlaceholder    override the current value of `inPlaceholder`
     * @param inOperation      override the current value of `inOperation`
     * @param parentOperation  if `inOperation` is true, this is the parent operation - nested
     *                         same operations are not grouped.
     * @return a Span
     */
    def nested(nestedExpression: Expr,
               placeholderOpen: String = placeholderOpen,
               inString: Boolean = inString,
               inPlaceholder: Boolean = inPlaceholder,
               inOperation: Boolean = inOperation,
               parentOperation: Option[String] = None): Span = {
      buildExpression(
          nestedExpression,
          placeholderOpen = placeholderOpen,
          inString = inString,
          inCommand = inCommand,
          inPlaceholder = inPlaceholder,
          inOperation = inOperation,
          parentOperation = parentOperation,
          stringModifier = stringModifier
      )
    }

    def option(name: String, value: Expr): Span = {
      val exprSpan = nested(value, inPlaceholder = true)
      val eqLiteral = Literal.fromNext(Symbols.Assignment, exprSpan)
      val nameLiteral = Literal.fromNext(name, eqLiteral)
      SpanSequence(Vector(nameLiteral, eqLiteral, exprSpan))
    }

    expr match {
      // literal values
      case ValueNone(loc) => Literal.fromStart(Symbols.None, loc)
      case ValueString(value, loc) =>
        val v = if (stringModifier.isDefined) {
          stringModifier.get(value)
        } else {
          value
        }
        val escaped = if (!inCommand) {
          Utils.escape(v)
        } else {
          v
        }
        Literal.fromStart(escaped, loc, quoting = inPlaceholder || !(inString || inCommand))
      case ValueBoolean(value, loc) => Literal.fromStart(value, loc)
      case ValueInt(value, loc)     => Literal.fromStart(value, loc)
      case ValueFloat(value, loc)   => Literal.fromStart(value, loc)
      case ExprArray(value, loc) =>
        BoundedContainer(
            value.map(nested(_)),
            Some(Literal.fromStart(Symbols.ArrayLiteralOpen, loc),
                 Literal.fromEnd(Symbols.ArrayLiteralClose, loc)),
            Some(Symbols.ArrayDelimiter),
            loc,
            wrapping = Wrapping.AllOrNone
        )
      case ExprPair(left, right, loc) =>
        BoundedContainer(
            Vector(nested(left), nested(right)),
            Some(Literal.fromStart(Symbols.GroupOpen, loc),
                 Literal.fromEnd(Symbols.GroupClose, loc)),
            Some(Symbols.ArrayDelimiter),
            loc
        )
      case ExprMap(value, loc) =>
        BoundedContainer(
            value.map {
              case ExprMember(k, v, itemText) =>
                KeyValue(nested(k), nested(v), bounds = itemText)
            },
            Some(Literal.fromStart(Symbols.MapOpen, loc), Literal.fromEnd(Symbols.MapClose, loc)),
            Some(Symbols.ArrayDelimiter),
            loc,
            Wrapping.Always,
            continue = false
        )
      case ExprObject(value, loc) =>
        BoundedContainer(
            value.map {
              case ExprMember(ValueString(k, loc), v, memberText) =>
                KeyValue(Literal.fromStart(k, loc), nested(v), bounds = memberText)
              case other =>
                throw new Exception(s"invalid object member ${other}")
            },
            Some(
                SpanSequence(
                    Literal.chainFromStart(Vector(Symbols.Object, Symbols.ObjectOpen), loc),
                    spacing = Spacing.On
                ),
                Literal.fromEnd(Symbols.ObjectClose, loc)
            ),
            Some(Symbols.ArrayDelimiter),
            bounds = loc,
            Wrapping.Always,
            continue = false
        )
      case ExprStruct(name, members, loc) =>
        val literalPrefix = if (targetVersion.exists(_ >= WdlVersion.V2)) {
          name
        } else {
          Symbols.Object
        }
        BoundedContainer(
            members.map {
              case ExprMember(ValueString(k, loc), v, memberText) =>
                KeyValue(Literal.fromStart(k, loc), nested(v), bounds = memberText)
              case other =>
                throw new Exception(s"invalid object member ${other}")
            },
            Some(
                SpanSequence(
                    Literal.chainFromStart(Vector(literalPrefix, Symbols.ObjectOpen), loc),
                    spacing = Spacing.On
                ),
                Literal.fromEnd(Symbols.ObjectClose, loc)
            ),
            Some(Symbols.ArrayDelimiter),
            bounds = loc,
            Wrapping.Always,
            continue = false
        )
      // placeholders
      case ExprPlaceholder(t, f, sep, default, value, loc) =>
        Placeholder(
            nested(value, inPlaceholder = true),
            placeholderOpen,
            options = Some(
                Vector(
                    t.map(e => option(Symbols.TrueOption, e)),
                    f.map(e => option(Symbols.FalseOption, e)),
                    sep.map(e => option(Symbols.SepOption, e)),
                    default.map(e => option(Symbols.DefaultOption, e))
                ).flatten
            ),
            inString = inString || inCommand,
            bounds = loc
        )
      case ExprCompoundString(value, loc) =>
        CompoundString(value.map(nested(_, inString = true)),
                       quoting = !(inString || inCommand),
                       loc)
      // other expressions need to be wrapped in a placeholder if they
      // appear in a string or command block
      case other =>
        val span = other match {
          case ExprIdentifier(id, loc) => Literal.fromStart(id, loc)
          case ExprAt(array, index, loc) =>
            val arraySpan = nested(array, inPlaceholder = inString || inCommand)
            val prefix = SpanSequence(
                Vector(arraySpan, Literal.fromPrev(Symbols.IndexOpen, arraySpan))
            )
            val suffix = Literal.fromEnd(Symbols.IndexClose, loc)
            BoundedContainer(
                Vector(nested(index, inPlaceholder = inString || inCommand)),
                Some(prefix, suffix),
                // TODO: shouldn't need a delimiter - index must be exactly length 1
                Some(Symbols.ArrayDelimiter),
                bounds = loc
            )
          case ExprIfThenElse(cond, tBranch, fBranch, loc) =>
            val condSpan = nested(cond, inOperation = false, inPlaceholder = inString || inCommand)
            val tSpan = nested(tBranch, inOperation = false, inPlaceholder = inString || inCommand)
            val fSpan = nested(fBranch, inOperation = false, inPlaceholder = inString || inCommand)
            BoundedContainer(
                Vector(
                    Literal.fromStart(Symbols.If, loc),
                    condSpan,
                    Literal.between(Symbols.Then, condSpan, tSpan),
                    tSpan,
                    Literal.between(Symbols.Else, tSpan, fSpan),
                    fSpan
                ),
                wrapping = Wrapping.AsNeeded,
                bounds = loc
            )
          case ExprApply(oper, Vector(value), loc) if Operator.All.contains(oper) =>
            val symbol = Operator.All(oper).symbol
            val operSpan = Literal.fromStart(symbol, loc)
            SpanSequence(Vector(operSpan, nested(value, inOperation = true)))
          case ExprApply(oper, Vector(lhs, rhs), loc) if Operator.All.contains(oper) =>
            val symbol = Operator.All(oper).symbol
            Operation(
                symbol,
                nested(lhs,
                       inPlaceholder = inString || inCommand,
                       inOperation = true,
                       parentOperation = Some(oper)),
                nested(rhs,
                       inPlaceholder = inString || inCommand,
                       inOperation = true,
                       parentOperation = Some(oper)),
                grouped = inOperation && !parentOperation.contains(oper),
                inString = inString || inCommand,
                loc
            )
          case ExprApply(funcName, elements, loc) =>
            val prefix = SpanSequence(
                Literal.chainFromStart(Vector(funcName, Symbols.FunctionCallOpen), loc)
            )
            val suffix = Literal.fromEnd(Symbols.FunctionCallClose, loc)
            BoundedContainer(
                elements.map(nested(_, inPlaceholder = inString || inCommand)),
                Some(prefix, suffix),
                Some(Symbols.ArrayDelimiter),
                loc
            )
          case ExprGetName(e, id, loc) =>
            val exprSpan = nested(e, inPlaceholder = inString || inCommand)
            val idLiteral = Literal.fromEnd(id, loc)
            SpanSequence(
                Vector(exprSpan, Literal.between(Symbols.Access, exprSpan, idLiteral), idLiteral)
            )
          case other => throw new Exception(s"Unrecognized expression $other")
        }
        if ((inString || inCommand) && !inPlaceholder) {
          Placeholder(span, placeholderOpen, inString = inString || inCommand, bounds = other.loc)
        } else {
          span
        }
    }
  }

  /**
    * Marker base class for Statements.
    */
  private trait Statement extends Multiline {

    /**
      * Format this statement. The `lineFormatter` must have `isLineBegun == false` on
      * both entry and exit.
      *
      * @param lineFormatter the lineFormatter
      */
    def format(lineFormatter: LineFormatter): Unit
  }

  private trait BoundedMultiline extends Multiline with Bounded {
    override def line: Int = bounds.line

    override def endLine: Int = bounds.endLine
  }

  private abstract class BoundedStatement(override val bounds: SourceLocation)
      extends Statement
      with BoundedMultiline {

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      formatContents(lineFormatter)
      lineFormatter.endLine()
    }

    /**
      * Format the contents of this statement. The `lineFormatter` must have
      * `isLineBegun == true` on both entry and exit.
      */
    protected def formatContents(lineFormatter: LineFormatter): Unit
  }

  private case class VersionStatement(version: Version) extends Statement with BoundedMultiline {
    override def bounds: SourceLocation = version.loc

    private val keywordToken = Literal.fromStart(Symbols.Version, version.loc)
    private val versionToken =
      Literal.fromEnd(targetVersion.getOrElse(version.value).name, version.loc)

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginLine()
      lineFormatter
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, versionToken))
      lineFormatter.endLine()
    }
  }

  private case class ImportStatement(importDoc: ImportDoc) extends BoundedStatement(importDoc.loc) {
    private val keywordToken = Literal.fromStart(Symbols.Import, importDoc.loc)
    // assuming URI comes directly after keyword
    private val uriLiteral = Literal.fromPrev(importDoc.addr.value, keywordToken)
    // assuming namespace comes directly after uri
    private val nameTokens = importDoc.name.map { name =>
      Literal.chainFromPrev(Vector(Symbols.As, name.value), uriLiteral)
    }
    private val aliasTokens = importDoc.aliases.map { alias =>
      Literal.chainFromStart(Vector(Symbols.Alias, alias.id1, Symbols.As, alias.id2), alias.loc)
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, uriLiteral))
      if (nameTokens.isDefined) {
        lineFormatter.appendAll(nameTokens.get)
      }
      aliasTokens.foreach { alias =>
        lineFormatter.derive(newWrapping = Wrapping.Always).appendAll(alias)
      }
    }
  }

  private abstract class Section(emtpyLineBetweenStatements: Boolean = false) extends Statement {
    def statements: Vector[Statement]

    protected lazy val sortedStatements: Vector[Statement] = statements.sortWith(_ < _)

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginSection(this)
      statements.head.format(lineFormatter)
      statements.tail.foreach { section =>
        if (emtpyLineBetweenStatements) {
          lineFormatter.emptyLine()
        }
        section.format(lineFormatter)
      }
      lineFormatter.endSection(this)
    }
  }

  private abstract class OpenSection(emtpyLineBetweenStatements: Boolean = false)
      extends Section(emtpyLineBetweenStatements) {
    override def line: Int = sortedStatements.head.line

    override def endLine: Int = sortedStatements.last.endLine
  }

  private abstract class InnerSection(val bounds: SourceLocation,
                                      emtpyLineBetweenStatements: Boolean = false)
      extends Section(emtpyLineBetweenStatements) {
    override def line: Int = bounds.line + 1

    override def endLine: Int = bounds.endLine - 1
  }

  private case class ImportsSection(imports: Vector[ImportDoc]) extends OpenSection {
    override val statements: Vector[Statement] = {
      imports.map(ImportStatement)
    }
  }

  private abstract class DeclarationBase(name: String,
                                         wdlType: Type,
                                         expr: Option[Expr] = None,
                                         override val bounds: SourceLocation)
      extends BoundedStatement(bounds) {

    private val typeSpan = DataType.fromWdlType(wdlType)
    // assuming name follows direclty after type
    private val nameLiteral = Literal.fromPrev(name, typeSpan)
    private val lhs = Vector(typeSpan, nameLiteral)
    private val rhs = expr.map { e =>
      val eqToken = Literal.fromPrev(Symbols.Assignment, nameLiteral)
      val exprAtom = buildExpression(e)
      Vector(eqToken, exprAtom)
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(lhs)
      if (rhs.isDefined) {
        lineFormatter.appendAll(rhs.get)
      }
    }
  }

  private case class DeclarationStatement(decl: Declaration)
      extends DeclarationBase(decl.name, decl.wdlType, decl.expr, decl.loc)

  private case class DeclarationsSection(declarations: Vector[Declaration]) extends OpenSection {
    override val statements: Vector[Statement] = {
      declarations.map(DeclarationStatement)
    }
  }

  private abstract class BlockStatement(keyword: String, override val bounds: SourceLocation)
      extends Statement
      with BoundedMultiline {

    def clause: Option[Span] = None

    def body: Option[Statement] = None

    protected val keywordLiteral: Literal = Literal.fromStart(keyword, bounds)

    private val clauseSpan: Option[Span] = clause
    // assume the open brace is on the same line as the keyword/clause
    private val openLiteral =
      Literal.fromPrev(Symbols.BlockOpen, clauseSpan.getOrElse(keywordLiteral))
    private val bodyStatement: Option[Statement] = body
    private val closeLiteral = Literal.fromEnd(Symbols.BlockClose, bounds)

    override def format(lineFormatter: LineFormatter): Unit = {
      lineFormatter.beginSection(this)
      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(Some(keywordLiteral), clauseSpan, Some(openLiteral)).flatten)
      if (bodyStatement.isDefined) {
        lineFormatter.endLine()
        bodyStatement.get.format(lineFormatter.derive(increaseIndent = true))
        lineFormatter.beginLine()
      }
      lineFormatter.append(closeLiteral)
      lineFormatter.endLine()
      lineFormatter.endSection(this)
    }
  }

  private case class InputsBlock(inputs: Vector[Declaration], override val bounds: SourceLocation)
      extends BlockStatement(Symbols.Input, bounds) {
    override def body: Option[Statement] = Some(DeclarationsSection(inputs))
  }

  /**
    * Due to the lack of a formal input section in draft-2, inputs and other declarations (i.e. those
    * that require evaluation and thus are not allowed as inputs) may be mixed in the source loc. A
    * TopDeclarations section takes both inputs and other declarations that appear at the top of a
    * workflow or task and formats them correctly using one Multiline for each sub-group of declarations
    * that covers all of the lines starting from the previous group (or startLine for the first element)
    * until the last line of the last declaration in the group.
    */
  private case class TopDeclarations(inputs: Vector[Statement],
                                     other: Vector[Statement],
                                     override val line: Int)
      extends Statement {

    override val endLine: Int = math.max(inputs.last.endLine, other.last.endLine)

    override def format(lineFormatter: LineFormatter): Unit = {
      val keywordLiteral: Literal = Literal.fromStartPosition(Symbols.Input, line = line)
      val openLiteral = Literal.fromPrev(Symbols.BlockOpen, keywordLiteral)

      lineFormatter.beginLine()
      lineFormatter.appendAll(Vector(keywordLiteral, openLiteral))
      lineFormatter.endLine()

      case class TopDeclarationsSection(override val statements: Vector[Statement],
                                        override val line: Int,
                                        override val endLine: Int)
          extends Section

      val inputFormatter = lineFormatter.derive(increaseIndent = true)
      var groupStart = line
      var inputItr = inputs.iterator.buffered
      var otherItr = other.iterator.buffered
      var otherGroups: Vector[TopDeclarationsSection] = Vector.empty

      def nextGroup(
          a: BufferedIterator[Statement],
          b: BufferedIterator[Statement]
      ): (Option[TopDeclarationsSection], BufferedIterator[Statement]) = {
        val (groupItr, aNew) = if (b.hasNext) {
          a.span(_.line < b.head.line)
        } else {
          (a, Iterator.empty)
        }
        val group = if (groupItr.nonEmpty) {
          val groupStatements = groupItr.toVector
          val end = groupStatements.last.endLine
          val section = TopDeclarationsSection(groupStatements, groupStart, end)
          groupStart = end + 1
          Some(section)
        } else {
          None
        }
        (group, aNew.buffered)
      }

      var lastInputLine = 0

      while (inputItr.hasNext) {
        val otherResult = nextGroup(otherItr, inputItr)
        if (otherResult._1.isDefined) {
          otherGroups :+= otherResult._1.get
        }
        otherItr = otherResult._2

        val inputResult = nextGroup(inputItr, otherItr)
        if (inputResult._1.isDefined) {
          val section = inputResult._1.get
          lastInputLine = section.endLine
          section.format(inputFormatter)
        }
        inputItr = inputResult._2
      }

      lineFormatter.beginLine()
      lineFormatter.append(Literal.fromEndPosition(Symbols.BlockClose, lastInputLine))
      lineFormatter.endLine()

      lineFormatter.emptyLine()

      if (otherGroups.nonEmpty) {
        otherGroups.foreach(group => group.format(lineFormatter))
      }
      if (otherItr.hasNext) {
        nextGroup(otherItr, inputItr)._1.get.format(lineFormatter)
      }
    }
  }

  private case class StructMemberStatement(member: StructMember)
      extends DeclarationBase(member.name, member.wdlType, bounds = member.loc)

  private case class MembersSection(members: Vector[StructMember],
                                    override val bounds: SourceLocation)
      extends InnerSection(bounds) {
    override val statements: Vector[Statement] = {
      members.map(StructMemberStatement)
    }
  }

  private def buildMeta(metaValue: MetaValue): Span = {
    metaValue match {
      // literal values
      case MetaValueNull(loc) => Literal.fromStart(Symbols.Null, loc)
      case MetaValueString(value, loc) =>
        Literal.fromStart(value, loc, quoting = true)
      case MetaValueBoolean(value, loc) => Literal.fromStart(value, loc)
      case MetaValueInt(value, loc)     => Literal.fromStart(value, loc)
      case MetaValueFloat(value, loc)   => Literal.fromStart(value, loc)
      case MetaValueArray(value, loc) =>
        BoundedContainer(
            value.map(buildMeta),
            Some(Literal.fromStart(Symbols.ArrayLiteralOpen, loc),
                 Literal.fromEnd(Symbols.ArrayLiteralClose, loc)),
            Some(Symbols.ArrayDelimiter),
            loc,
            wrapping = Wrapping.AllOrNone,
            continue = false
        )
      case MetaValueObject(value, loc) =>
        BoundedContainer(
            value.map {
              case MetaKV(k, v, memberText) =>
                KeyValue(Literal.fromStart(k, memberText), buildMeta(v), bounds = memberText)
            },
            Some(Literal.fromStart(Symbols.ObjectOpen, loc),
                 Literal.fromEnd(Symbols.ObjectClose, loc)),
            Some(Symbols.ArrayDelimiter),
            bounds = loc,
            Wrapping.Always,
            continue = false
        )
    }
  }

  private case class MetaKVStatement(id: String,
                                     value: MetaValue,
                                     override val bounds: SourceLocation)
      extends BoundedStatement(bounds) {
    private val idToken = Literal.fromStart(id, bounds)
    private val delimToken = Literal.fromPrev(Symbols.KeyValueDelimiter, idToken)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildMeta(value)

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter.derive(newWrapping = Wrapping.Never).appendAll(Vector(SpanSequence(lhs), rhs))
    }
  }

  private case class MetadataSection(metaKV: Vector[MetaKV], override val bounds: SourceLocation)
      extends InnerSection(bounds) {
    override val statements: Vector[Statement] = {
      metaKV.map(kv => MetaKVStatement(kv.id, kv.value, kv.loc))
    }
  }

  private case class StructBlock(struct: TypeStruct)
      extends BlockStatement(Symbols.Struct, struct.loc) {
    override def clause: Option[Span] = Some(
        Literal.fromPrev(struct.name, keywordLiteral)
    )

    override def body: Option[Statement] =
      Some(MembersSection(struct.members, struct.loc))
  }

  private case class OutputsBlock(outputs: OutputSection)
      extends BlockStatement(Symbols.Output, outputs.loc) {
    override def body: Option[Statement] =
      Some(
          DeclarationsSection(outputs.parameters)
      )
  }

  private case class MetaBlock(meta: MetaSection) extends BlockStatement(Symbols.Meta, meta.loc) {
    override def body: Option[Statement] =
      Some(MetadataSection(meta.kvs, meta.loc))
  }

  private case class ParameterMetaBlock(parameterMeta: ParameterMetaSection)
      extends BlockStatement(Symbols.ParameterMeta, parameterMeta.loc) {
    override def body: Option[Statement] =
      Some(MetadataSection(parameterMeta.kvs, parameterMeta.loc))
  }

  private def splitWorkflowElements(elements: Vector[WorkflowElement]): Vector[Statement] = {
    var statements: Vector[Statement] = Vector.empty
    var declarations: Vector[Declaration] = Vector.empty

    elements.foreach {
      case declaration: Declaration => declarations :+= declaration
      case other =>
        if (declarations.nonEmpty) {
          statements :+= DeclarationsSection(declarations)
          declarations = Vector.empty
        }
        statements :+= (other match {
          case call: Call               => CallBlock(call)
          case scatter: Scatter         => ScatterBlock(scatter)
          case conditional: Conditional => ConditionalBlock(conditional)
          case other                    => throw new Exception(s"Unexpected workflow body element $other")
        })
    }

    if (declarations.nonEmpty) {
      statements :+= DeclarationsSection(declarations)
    }

    statements
  }

  private case class WorkflowElementBody(override val statements: Vector[Statement])
      extends OpenSection(emtpyLineBetweenStatements = true)

  private case class CallInputArgsContainer(args: Vector[Container])
      extends Container(args, delimiter = Some(Symbols.ArrayDelimiter), wrapping = Wrapping.Always) {
    require(args.nonEmpty)

    override def line: Int = args.head.line

    override def column: Int = args.head.column

    override def endLine: Int = args.last.endLine

    override def endColumn: Int = args.last.endColumn
  }

  private case class OpenSpacedContainer(items: Vector[Span], ends: Option[(Span, Span)] = None)
      extends Container(items, ends = ends) {

    require(items.nonEmpty)

    override def line: Int = ends.map(_._1.line).getOrElse(items.head.line)

    override def endLine: Int = ends.map(_._2.endLine).getOrElse(items.last.line)

    override def column: Int = ends.map(_._1.column).getOrElse(items.head.column)

    override def endColumn: Int = ends.map(_._2.endColumn).getOrElse(items.last.endColumn)
  }

  private case class CallInputsStatement(inputs: CallInputs) extends BoundedStatement(inputs.loc) {
    private val key = Literal.fromStart(Symbols.Input, inputs.loc)
    private val value = inputs.value.map { inp =>
      val nameToken = Literal.fromStart(inp.name, inp.loc)
      val exprSpan = buildExpression(inp.expr)
      BoundedContainer(
          Vector(nameToken, Literal.between(Symbols.Assignment, nameToken, exprSpan), exprSpan),
          bounds = inp.loc
      )
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      val kv = KeyValue(key, CallInputArgsContainer(value), bounds = inputs.loc)
      kv.formatContents(lineFormatter)
    }
  }

  private case class CallBlock(call: Call) extends BlockStatement(Symbols.Call, call.loc) {
    override def clause: Option[Span] = Some(
        if (call.alias.isDefined) {
          val alias = call.alias.get
          // assuming all parts of the clause are adjacent
          val tokens =
            Literal.chainFromPrev(Vector(call.name, Symbols.As, alias.name), keywordLiteral)
          OpenSpacedContainer(tokens)
        } else {
          Literal.fromPrev(call.name, keywordLiteral)
        }
    )

    override def body: Option[Statement] =
      if (call.inputs.isDefined) {
        Some(CallInputsStatement(call.inputs.get))
      } else {
        None
      }
  }

  private case class ScatterBlock(scatter: Scatter)
      extends BlockStatement(Symbols.Scatter, scatter.loc) {

    override def clause: Option[Span] = {
      // assuming all parts of the clause are adjacent
      val openToken = Literal.fromPrev(Symbols.GroupOpen, keywordLiteral)
      val idToken = Literal.fromPrev(scatter.identifier, openToken)
      val inToken = Literal.fromPrev(Symbols.In, idToken)
      val exprAtom = buildExpression(scatter.expr)
      val closeToken = Literal.fromPrev(Symbols.GroupClose, exprAtom)
      Some(
          OpenSpacedContainer(
              Vector(idToken, inToken, exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(WorkflowElementBody(splitWorkflowElements(scatter.body)))
  }

  private case class ConditionalBlock(conditional: Conditional)
      extends BlockStatement(Symbols.If, conditional.loc) {
    override def clause: Option[Span] = {
      val exprAtom = buildExpression(conditional.expr)
      val openToken = Literal.fromNext(Symbols.GroupOpen, exprAtom)
      val closeToken = Literal.fromPrev(Symbols.GroupClose, exprAtom)
      Some(
          OpenSpacedContainer(
              Vector(exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(WorkflowElementBody(splitWorkflowElements(conditional.body)))
  }

  private case class WorkflowSections(workflow: Workflow)
      extends InnerSection(workflow.loc, emtpyLineBetweenStatements = true) {

    override val statements: Vector[Statement] = {
      val bodyElements = splitWorkflowElements(workflow.body)
      val (topSection, body) = if (workflow.input.isDefined) {
        if (bodyElements.nonEmpty && bodyElements.head.isInstanceOf[DeclarationsSection]) {
          val inputDecls = workflow.input.map(_.parameters.map(DeclarationStatement))
          (Some(
               TopDeclarations(
                   inputDecls.get,
                   bodyElements.head.asInstanceOf[DeclarationsSection].statements,
                   bounds.line + 1
               )
           ),
           bodyElements.tail)
        } else {
          (workflow.input.map(inp => InputsBlock(inp.parameters, inp.loc)), bodyElements)
        }
      } else {
        (None, bodyElements)
      }
      val bodySection = if (body.nonEmpty) {
        Some(WorkflowElementBody(body))
      } else {
        None
      }
      Vector(
          topSection,
          bodySection,
          workflow.output.map(OutputsBlock),
          workflow.meta.map(
              MetaBlock
          ),
          workflow.parameterMeta.map(ParameterMetaBlock)
      ).flatten
    }
  }

  private case class WorkflowBlock(workflow: Workflow)
      extends BlockStatement(Symbols.Workflow, workflow.loc) {

    override def clause: Option[Span] = Some(Literal.fromPrev(workflow.name, keywordLiteral))

    override def body: Option[Statement] = Some(WorkflowSections(workflow))
  }

  private case class CommandBlock(command: CommandSection) extends BoundedStatement(command.loc) {
    // The command block is considered "preformatted" in that we don't try to reformat it.
    // However, we do need to try to indent it correclty. We do this by detecting the amount
    // of indent used on the first non-empty line and remove that from every line and replace
    // it by the lineFormatter's current indent level.
    private val commandStartRegexp = "(?s)^([^\n\r]*)[\n\r]*(.*)$".r
    private val commandEndRegexp = "\\s+$".r
    private val commentRegexp = "#+\\s*(.+)".r
    //private val commandStartRegexp = "(?s)^(.*?)[\n\r]+([ \\t]*)(.*)".r
    //private val commandSingletonRegexp = "(?s)^(.*?)[\n\r]*[ \\t]*(.*?)\\s*$".r

    // check whether there is at least one non-whitespace command part
    private def hasCommand: Boolean = {
      command.parts.exists {
        case ValueString(value, _) => value.trim.nonEmpty
        case _                     => true
      }
    }

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(
          Literal.chainFromStart(Vector(Symbols.Command, Symbols.CommandOpen), command.loc)
      )
      if (hasCommand) {
        // The parser swallows anyting after the opening token ('{' or '<<<')
        // as part of the comment block, so we need to parse out any in-line
        // comment and append it separately.
        val (headExpr: Expr, indent, comment) = command.parts.head match {
          case ValueString(value, loc) =>
            value match {
              case commandStartRegexp(first, rest) =>
                first.trim match {
                  case s
                      if (
                          s.isEmpty || s.startsWith(Symbols.Comment)
                      ) && rest.trim.isEmpty && command.parts.size == 1 =>
                    // command block is empty
                    (ValueString("", loc), None, Some(s))
                  case s if (s.isEmpty || s.startsWith(Symbols.Comment)) && rest.trim.isEmpty =>
                    // weird case, like there is a placeholder in the comment - we don't want to break
                    // anything so we'll just format the whole block as-is
                    (s, None, None)
                  case s if s.isEmpty || s.startsWith(Symbols.Comment) =>
                    // opening line was empty or a comment
                    val (ws, trimmedRest) = rest match {
                      case leadingWhitespaceRegexp(ws, trimmedRest) => (Some(ws), trimmedRest)
                      case _                                        => (None, rest)
                    }
                    // the first line will be indented, so we need to trim the indent from `rest`
                    (ValueString(trimmedRest, loc), ws, Some(s))
                  case s if rest.trim.isEmpty =>
                    // single-line expression
                    (ValueString(s, loc), None, None)
                  case s =>
                    // opening line has some real content, so just trim any leading whitespace
                    val ws = leadingWhitespaceRegexp
                      .findFirstMatchIn(rest)
                      .map(m => m.group(1))
                    (ValueString(s"${s}\n${rest}", loc), ws, None)
                }
              case other =>
                throw new RuntimeException(s"unexpected command part ${other}")
            }
          case other =>
            (other, None, None)
        }

        def trimLast(last: Expr): Expr = {
          last match {
            case ValueString(s, loc) =>
              // If the last part is just the whitespace before the close block, throw it out
              ValueString(commandEndRegexp.replaceFirstIn(s, ""), loc)
            case other =>
              other
          }
        }

        val newParts = if (command.parts.size == 1) {
          Vector(trimLast(headExpr))
        } else {
          val last = Vector(trimLast(command.parts.last))
          Vector(headExpr) ++ (
              if (command.parts.size == 2) {
                last
              } else {
                command.parts.slice(1, command.parts.size - 1) ++ last
              }
          )
        }

        comment match {
          case Some(commentRegexp(commentContent)) =>
            lineFormatter.addInlineComment(command.loc.line, commentContent)
          case _ => ()
        }
        lineFormatter.endLine()

        val bodyFormatter = lineFormatter.derive(increaseIndent = true,
                                                 newSpacing = Spacing.Off,
                                                 newWrapping = Wrapping.Never)

        val replaceIndent = indent.map { ws =>
          // Function to replace indenting in command block expressions with the current
          // indent level of the formatter
          val indentRegexp = s"\n${ws}".r
          val replacement = s"\n${bodyFormatter.getIndent()}"
          (s: String) => indentRegexp.replaceAllIn(s, replacement)
        }

        bodyFormatter.beginLine()
        newParts.foreach { expr =>
          bodyFormatter.append(
              buildExpression(
                  expr,
                  placeholderOpen = Symbols.PlaceholderOpenTilde,
                  inCommand = true,
                  stringModifier = replaceIndent
              )
          )
        }
        bodyFormatter.endLine()

        lineFormatter.beginLine()
      }

      lineFormatter.append(Literal.fromEnd(Symbols.CommandClose, command.loc))
    }
  }

  private case class KVStatement(id: String, expr: Expr, override val bounds: SourceLocation)
      extends BoundedStatement(bounds) {
    private val idToken = Literal.fromStart(id, bounds)
    private val delimToken = Literal.fromPrev(Symbols.KeyValueDelimiter, idToken)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildExpression(expr)

    override def formatContents(lineFormatter: LineFormatter): Unit = {
      lineFormatter.appendAll(Vector(SpanSequence(lhs), rhs))
    }
  }

  private case class RuntimeMetadataSection(runtimeKV: Vector[RuntimeKV],
                                            override val bounds: SourceLocation)
      extends InnerSection(bounds) {
    override val statements: Vector[Statement] = {
      runtimeKV.map(kv => KVStatement(kv.id, kv.expr, kv.loc))
    }
  }

  private case class RuntimeBlock(runtime: RuntimeSection)
      extends BlockStatement(Symbols.Runtime, runtime.loc) {
    override def body: Option[Statement] =
      Some(RuntimeMetadataSection(runtime.kvs, runtime.loc))
  }

  private case class HintsBlock(hints: HintsSection)
      extends BlockStatement(Symbols.Hints, hints.loc) {
    override def body: Option[Statement] =
      Some(MetadataSection(hints.kvs, hints.loc))
  }

  private case class TaskSections(task: Task)
      extends InnerSection(task.loc, emtpyLineBetweenStatements = true) {

    override val statements: Vector[Statement] = {
      val privateVars = task.privateVariables match {
        case v: Vector[Declaration] if v.nonEmpty => Some(DeclarationsSection(v))
        case _                                    => None
      }
      val (topSection, declSection) =
        if (task.input.isDefined && privateVars.isDefined) {
          val inputVars = task.input.map(_.parameters.map(DeclarationStatement))
          (Some(
               TopDeclarations(
                   inputVars.get,
                   privateVars.get.statements,
                   line
               )
           ),
           None)
        } else {
          (task.input.map(inp => InputsBlock(inp.parameters, inp.loc)), privateVars)
        }
      Vector(
          topSection,
          declSection,
          Some(CommandBlock(task.command)),
          task.output.map(OutputsBlock),
          task.runtime.map(RuntimeBlock),
          task.hints.map(HintsBlock),
          task.meta.map(MetaBlock),
          task.parameterMeta.map(ParameterMetaBlock)
      ).flatten
    }
  }

  private case class TaskBlock(task: Task) extends BlockStatement(Symbols.Task, task.loc) {
    override def clause: Option[Span] =
      Some(Literal.fromPrev(task.name, keywordLiteral))

    override def body: Option[Statement] = Some(TaskSections(task))
  }

  private case class DocumentSections(document: Document) extends Statement with BoundedMultiline {
    override def bounds: SourceLocation = document.loc

    override def format(lineFormatter: LineFormatter): Unit = {
      // the version statement must be the first line in the file
      // so we start the section after appending it just in case
      // there were comments at the top of the source file
      val versionStatement = VersionStatement(document.version)
      versionStatement.format(lineFormatter)
      lineFormatter.beginSection(this)

      val imports = document.elements.collect { case imp: ImportDoc => imp }
      if (imports.nonEmpty) {
        lineFormatter.emptyLine()
        ImportsSection(imports).format(lineFormatter)
      }

      document.elements
        .collect {
          case struct: TypeStruct => StructBlock(struct)
        }
        .foreach { struct =>
          lineFormatter.emptyLine()
          struct.format(lineFormatter)
        }

      if (document.workflow.isDefined) {
        lineFormatter.emptyLine()
        WorkflowBlock(document.workflow.get).format(lineFormatter)
      }

      document.elements
        .collect {
          case task: Task => TaskBlock(task)
        }
        .foreach { task =>
          lineFormatter.emptyLine()
          task.format(lineFormatter)
        }

      lineFormatter.endSection(this)
    }
  }

  def formatElement(element: Element, comments: CommentMap = CommentMap.empty): Vector[String] = {
    val stmt = element match {
      case d: Document => DocumentSections(d)
      case t: Task     => TaskBlock(t)
      case w: Workflow => WorkflowBlock(w)
      case other =>
        throw new RuntimeException(s"Formatting element of type ${other.getClass} not supported")
    }
    val lineFormatter = LineFormatter(comments)
    stmt.format(lineFormatter)
    lineFormatter.toVector
  }

  def formatDocument(document: Document): Vector[String] = {
    val version = targetVersion.getOrElse(document.version.value)
    if (version < WdlVersion.V1) {
      throw new Exception(s"WDL version ${version} is not supported")
    }
    formatElement(document, document.comments)
  }

  def formatDocuments(docSource: FileNode): Map[FileNode, Vector[String]] = {
    Parsers(followImports, fileResolver, logger = logger)
      .getDocumentWalker[Map[FileNode, Vector[String]]](docSource, Map.empty)
      .walk { (doc, results) =>
        results + (doc.source -> formatDocument(doc))
      }
  }
}
