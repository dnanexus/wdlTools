/**
  * TODO:
  * - rewrite code generators using ideas from
  *   - http://journal.stuffwithstuff.com/2015/09/08/the-hardest-program-ive-ever-written/
  *   - https://github.com/prettier/prettier-printer
  *   - https://jyp.github.io/posts/towards-the-prettiest-printer.html
  *   - https://github.com/dprint/dprint/blob/master/README.md
  */
package wdlTools.generators.code

import dx.util.{AddressableFileSource, FileSource, FileUtils, LocalFileSource}

import scala.reflect.runtime.universe._
import wdlTools.syntax.{BuiltinSymbols, Quoting}

import java.nio.file.{Path, Paths}
import scala.collection.immutable.SeqMap
import scala.jdk.CollectionConverters._

object Indenting extends Enumeration {
  type Indenting = Value
  val Always, IfNotIndented, Dedent, Reset, Never = Value
}

object Wrapping extends Enumeration {
  type Wrapping = Value
  val Never, // Never wrap adjacent statements
  AsNeeded, // Wrap adjacent statements as needed (i.e. when the statement
  // is longer than the maximum line length)
  AllOrNone, // Either wrap all statements or none of them
  Always // Always wrap
  = Value
}

object Spacing extends Enumeration {
  type Spacing = Value
  val On, Off = Value
}

trait Sized {

  /**
    * The length of the element in characters, if it were formatted without line-wrapping.
    */
  def length: Int

  /**
    * The length of the element's first line, if it were formatted with line-wrapping.
    */
  def firstLineLength: Int = length
}

trait ExpressionState {
  def canAdvanceTo(state: ExpressionState): Boolean
}

trait InitialState extends ExpressionState {
  override def canAdvanceTo(state: ExpressionState): Boolean = {
    state match {
      case _: InStringState | _: InOperationState => true
      case _                                      => false
    }
  }
}

/**
  * The initial expression state.
  */
object StartState extends InitialState

/**
  * The state of being within a string expression but not within a placeholder.
  * @param quoting the type of quoting for the string
  */
case class InStringState(quoting: Quoting.Quoting) extends ExpressionState {
  override def canAdvanceTo(state: ExpressionState): Boolean = {
    state match {
      case _: InStringState   => true
      case InPlaceholderState => true
      case _                  => false
    }
  }
}

/**
  * The state of being within a placeholder.
  */
object InPlaceholderState extends InitialState

/**
  * The state of being within a built-in operation.
  * @param oper the operation
  */
case class InOperationState(oper: Option[String] = None) extends InitialState

case class ExpressionContext(inCommand: Boolean, states: List[ExpressionState]) {
  lazy val placeholderOpen: String = if (inCommand) {
    Symbols.PlaceholderOpenTilde
  } else {
    Symbols.PlaceholderOpenDollar
  }

  def advanceTo(nextState: ExpressionState): ExpressionContext = {
    if (states.head.canAdvanceTo(nextState)) {
      copy(states = nextState :: states)
    } else {
      throw new Exception(s"cannot advance from ${states.head} to ${nextState}")
    }
  }

  def getStringQuoting(resetInPlaceholder: Boolean = false): Option[Quoting.Quoting] = {
    states.collectFirst {
      case InStringState(quoting)                   => Some(quoting)
      case InPlaceholderState if resetInPlaceholder => None
    }.flatten
  }

  def inString(quoted: Boolean = false, resetInPlaceholder: Boolean = false): Boolean = {
    getStringQuoting(resetInPlaceholder) match {
      case Some(Quoting.Single | Quoting.Double) => true
      case Some(_) if !quoted                    => true
      case _                                     => false
    }
  }

  def inPlaceholder: Boolean = {
    states.exists {
      case InPlaceholderState => true
      case _                  => false
    }
  }

  def groupOperation(oper: String): Boolean = {
    states.headOption match {
      case Some(InOperationState(Some(parentOper))) if parentOper != oper => true
      case _                                                              => false
    }
  }
}

object ExpressionContext {
  lazy val default: ExpressionContext =
    ExpressionContext(inCommand = false, List(StartState))
  lazy val command: ExpressionContext =
    ExpressionContext(inCommand = true, List(InStringState(Quoting.None), StartState))
}

object Utils {

  /**
    * Escapes special characters in a String.
    */
  def escape(raw: String): String = {
    val quoted = Literal(Constant(raw)).toString
    quoted.substring(1, quoted.length - 1)
  }

  def quoteString(raw: String): (String, Quoting.Quoting) = {
    if (raw.contains("'") && raw.contains('"')) {
      (escape(raw), Quoting.Double)
    } else if (raw.contains('"')) {
      (raw, Quoting.Single)
    } else {
      (raw, Quoting.Double)
    }
  }

  def writeDocuments[T <: FileSource](
      docs: SeqMap[T, Iterable[String]],
      outputDir: Option[Path] = None,
      overwrite: Boolean = false
  ): Map[AddressableFileSource, Path] = {
    val writableDocs = docs.flatMap {
      case (fs: AddressableFileSource, lines) if outputDir.isDefined => Some(fs -> lines)
      case (local: LocalFileSource, lines) if overwrite =>
        FileUtils.writeFileContent(local.canonicalPath,
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = true)
        None
      case (fs, lines) =>
        println(s"${fs.toString}\n${lines.mkString(System.lineSeparator())}")
        None
    }

    if (writableDocs.nonEmpty) {
      // determine the common ancestor path between all generated files
      val rootPath = Paths.get("/")
      val sourceToRelPath = writableDocs.keys.map { fs =>
        fs -> rootPath.relativize(Paths.get(fs.folder).resolve(fs.name))
      }.toMap
      val pathComponents = sourceToRelPath.values.map(_.iterator().asScala.toVector)
      // don't include the file name in the path components
      val shortestPathSize = pathComponents.map(_.size - 1).min
      val commonPathComponents = pathComponents
        .map(_.slice(0, shortestPathSize))
        .transpose
        .iterator
        .map(_.toSet)
        .takeWhile(_.size == 1)
        .map(_.head.toString)
        .toVector
      val commonAncestor = Paths.get(commonPathComponents.head, commonPathComponents.tail: _*)
      writableDocs.map {
        case (fs, lines) =>
          val outputPath = outputDir.get.resolve(commonAncestor.relativize(sourceToRelPath(fs)))
          FileUtils.writeFileContent(outputPath,
                                     lines.mkString(System.lineSeparator()),
                                     overwrite = overwrite)
          fs -> outputPath
      }
    } else {
      Map.empty
    }
  }
}

/**
  * Pre-defined Strings.
  */
object Symbols extends BuiltinSymbols {
  // keywords
  val Alias: String = "alias"
  val As: String = "as"
  val Call: String = "call"
  val Command: String = "command"
  val Else: String = "else"
  val Hints: String = "hints"
  val If: String = "if"
  val Import: String = "import"
  val In: String = "in"
  val Input: String = "input"
  val Meta: String = "meta"
  val Object: String = "object"
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
  val None: String = "None"

  // data types
  val ArrayType: String = "Array"
  val MapType: String = "Map"
  val PairType: String = "Pair"
  val ObjectType: String = "Object"
  val StringType: String = "String"
  val BooleanType: String = "Boolean"
  val IntType: String = "Int"
  val FloatType: String = "Float"
  val FileType: String = "File"
  val DirectoryType: String = "Directory"

  // operators, etc
  val Access: String = "."
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
  val DefaultOption: String = "default"
  val DoubleQuoteOpen: String = "\""
  val DoubleQuoteClose: String = "\""
  val FalseOption: String = "false"
  val FunctionCallOpen: String = "("
  val FunctionCallClose: String = ")"
  val GroupOpen: String = "("
  val GroupClose: String = ")"
  val IndexOpen: String = "["
  val IndexClose: String = "]"
  val KeyValueDelimiter: String = ":"
  val MapOpen: String = "{"
  val MapClose: String = "}"
  val MemberDelimiter: String = ","
  val NonEmpty: String = "+"
  val ObjectOpen: String = "{"
  val ObjectClose: String = "}"
  val Optional: String = "?"
  val PlaceholderOpenTilde: String = "~{"
  val PlaceholderOpenDollar: String = "${"
  val PlaceholderClose: String = "}"
  val SepOption: String = "sep"
  val SingleQuoteOpen: String = "'"
  val SingleQuoteClose: String = "'"
  val TrueOption: String = "true"
  val TypeParamOpen: String = "["
  val TypeParamClose: String = "]"
  val TypeParamDelimiter: String = ","
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
      SingleQuoteOpen -> SingleQuoteClose,
      DoubleQuoteOpen -> DoubleQuoteClose,
      TypeParamOpen -> TypeParamClose
  )
}

/**
  * A wrapper around a primitive that enables passing a mutable variable by reference.
  * @param value the flag value
  */
case class MutableHolder[T](var value: T)
