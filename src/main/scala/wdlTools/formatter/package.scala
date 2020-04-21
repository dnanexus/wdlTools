package wdlTools.formatter

import wdlTools.formatter.Indenting.Indenting
import wdlTools.formatter.Wrapping.Wrapping
import wdlTools.syntax.{Comment, TextSource}

object Indenting extends Enumeration {
  type Indenting = Value
  val Always, IfNotIndented, Dedent, Reset, Never = Value
}

object Wrapping extends Enumeration {
  type Wrapping = Value
  val Always, AsNeeded, Never = Value
}

trait Chunk {
  def wrapAll: Boolean = false

  def format(lineFormatter: LineFormatter): Unit

  def textSource: TextSource
}

abstract class LineFormatter(defaultIndenting: Indenting = Indenting.IfNotIndented,
                             defaultSpacing: String = " ") {
  def preformatted(): LineFormatter

  def indented(indenting: Indenting = defaultIndenting): LineFormatter

  def atLineStart: Boolean

  def lengthRemaining: Int

  def emptyLine(): Unit

  def beginLine(): Unit

  def endLine(wrap: Boolean = false, indenting: Indenting = defaultIndenting): Unit

  def appendComments(comment: Vector[Comment]): Unit

  def appendString(value: String): Unit

  def appendChunk(chunk: Chunk, spacing: String = defaultSpacing): Unit

  def appendAll(chunks: Vector[Chunk],
                wrapping: Wrapping = Wrapping.AsNeeded,
                spacing: String = defaultSpacing): Unit

  def toVector: Vector[String]
}

/**
  * Pre-defined Strings.
  */
object Symbols {
  // keywords
  val Alias: String = "alias"
  val As: String = "as"
  val Call: String = "call"
  val Command: String = "command"
  val Else: String = "else"
  val If: String = "if"
  val Import: String = "import"
  val In: String = "in"
  val Input: String = "input"
  val Meta: String = "meta"
  val Output: String = "output"
  val ParameterMeta: String = "parameter_meta"
  val Runtime: String = "runtime"
  val Scatter: String = "scatter"
  val Struct: String = "struct"
  val Task: String = "task"
  val Then: String = "then"
  val Version: String = "version"
  val Workflow: String = "workflow"
  val Null: String = "null"

  // data types
  val ArrayType: String = "Array"
  val MapType: String = "Map"
  val PairType: String = "Pair"
  val ObjectType: String = "Object"
  val StringType: String = "String"
  val BooleanType: String = "Boolean"
  val IntType: String = "Int"
  val FloatType: String = "Float"

  // operators, etc
  val Access: String = "."
  val Addition: String = "+"
  val ArrayDelimiter: String = ","
  val ArrayLiteralOpen: String = "["
  val ArrayLiteralClose: String = "]"
  val Assignment: String = "="
  val BlockOpen: String = "{"
  val BlockClose: String = "}"
  val CommandOpen: String = "<<<"
  val CommandClose: String = ">>>"
  val ClauseOpen: String = "("
  val ClauseClose: String = ")"
  val DefaultOption: String = "default="
  val Division: String = "/"
  val Equality: String = "=="
  val FalseOption: String = "false="
  val FunctionCallOpen: String = "("
  val FunctionCallClose: String = ")"
  val GreaterThan: String = ">"
  val GreaterThanOrEqual: String = ">="
  val GroupOpen: String = "("
  val GroupClose: String = ")"
  val IndexOpen: String = "["
  val IndexClose: String = "]"
  val Inequality: String = "!="
  val KeyValueDelimiter: String = ":"
  val LessThan: String = "<"
  val LessThanOrEqual: String = "<="
  val LogicalAnd: String = "&&"
  val LogicalOr: String = "||"
  val LogicalNot: String = "!"
  val MapOpen: String = "{"
  val MapClose: String = "}"
  val MemberDelimiter: String = ","
  val Multiplication: String = "*"
  val NonEmpty: String = "+"
  val ObjectOpen: String = "{"
  val ObjectClose: String = "}"
  val Optional: String = "?"
  val PlaceholderOpenTilde: String = "~{"
  val PlaceholderOpenDollar: String = "${"
  val PlaceholderClose: String = "}"
  val QuoteOpen: String = "\""
  val QuoteClose: String = "\""
  val Remainder: String = "%"
  val SepOption: String = "sep="
  val Subtraction: String = "-"
  val TrueOption: String = "true="
  val TypeParamOpen: String = "["
  val TypeParamClose: String = "]"
  val TypeParamDelimiter: String = ","
  val UnaryMinus: String = "-"
  val UnaryPlus: String = "+"
  val Comment: String = "#"
  val PreformattedComment: String = "##"

  val TokenPairs = Map(
      ArrayLiteralOpen -> ArrayLiteralClose,
      BlockOpen -> BlockClose,
      ClauseOpen -> ClauseClose,
      CommandOpen -> CommandClose,
      FunctionCallOpen -> FunctionCallClose,
      GroupOpen -> GroupClose,
      IndexOpen -> IndexClose,
      MapOpen -> MapClose,
      ObjectOpen -> ObjectClose,
      PlaceholderOpenTilde -> PlaceholderClose,
      PlaceholderOpenDollar -> PlaceholderClose,
      QuoteOpen -> QuoteClose,
      TypeParamOpen -> TypeParamClose
  )
}
