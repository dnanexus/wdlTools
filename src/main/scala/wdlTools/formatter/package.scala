package wdlTools.formatter

import wdlTools.formatter.Wrapping.Wrapping

object Indenting extends Enumeration {
  type Indenting = Value
  val Always, IfNotIndented, Dedent, Never = Value
}

object Wrapping extends Enumeration {
  type Wrapping = Value
  val Always, AsNeeded, Never = Value
}

trait Chunk {
  def format(lineFormatter: LineFormatter): Unit
}

abstract class Atom extends Chunk {
  def length: Int

  def format(lineFormatter: LineFormatter): Unit = {
    val space = if (lineFormatter.atLineStart) {
      ""
    } else {
      " "
    }
    if (lineFormatter.lengthRemaining < space.length + this.length) {
      lineFormatter.endLineUnlessEmpty(wrap = true)
      lineFormatter.append(this)
    } else {
      lineFormatter.append(space)
      lineFormatter.append(this)
    }
  }
}

case class Token(value: String) extends Atom {
  override def toString: String = {
    value
  }

  override def length: Int = {
    value.length
  }
}

/**
  * Pre-defined Tokens.
  */
object Token {
  // keywords
  val Alias: Token = Token("alias")
  val As: Token = Token("as")
  val Call: Token = Token("call")
  val Command: Token = Token("command")
  val Else: Token = Token("else")
  val If: Token = Token("if")
  val Import: Token = Token("import")
  val In: Token = Token("in")
  val Input: Token = Token("input")
  val Meta: Token = Token("meta")
  val Output: Token = Token("output")
  val ParameterMeta: Token = Token("parameter_meta")
  val Runtime: Token = Token("runtime")
  val Scatter: Token = Token("scatter")
  val Struct: Token = Token("struct")
  val Task: Token = Token("task")
  val Then: Token = Token("then")
  val Version: Token = Token("version")
  val Workflow: Token = Token("workflow")

  // data types
  val ArrayType: Token = Token("Array")
  val MapType: Token = Token("Map")
  val PairType: Token = Token("Pair")
  val ObjectType: Token = Token("Object")
  val StringType: Token = Token("String")
  val BooleanType: Token = Token("Boolean")
  val IntType: Token = Token("Int")
  val FloatType: Token = Token("Float")

  // symbols
  val Access: Token = Token(".")
  val Addition: Token = Token("+")
  val ArrayDelimiter: Token = Token(",")
  val ArrayLiteralOpen: Token = Token("[")
  val ArrayLiteralClose: Token = Token("]")
  val Assignment: Token = Token("=")
  val BlockOpen: Token = Token("{")
  val BlockClose: Token = Token("}")
  val CommandOpen: Token = Token("<<<")
  val CommandClose: Token = Token(">>>")
  val ClauseOpen: Token = Token("(")
  val ClauseClose: Token = Token(")")
  val DefaultOption: Token = Token("default=")
  val Division: Token = Token("/")
  val Equality: Token = Token("==")
  val FalseOption: Token = Token("false=")
  val FunctionCallOpen: Token = Token("(")
  val FunctionCallClose: Token = Token(")")
  val GreaterThan: Token = Token(">")
  val GreaterThanOrEqual: Token = Token(">=")
  val GroupOpen: Token = Token("(")
  val GroupClose: Token = Token(")")
  val IndexOpen: Token = Token("[")
  val IndexClose: Token = Token("]")
  val Inequality: Token = Token("!=")
  val KeyValueDelimiter: Token = Token(":")
  val LessThan: Token = Token("<")
  val LessThanOrEqual: Token = Token("<=")
  val LogicalAnd: Token = Token("&&")
  val LogicalOr: Token = Token("||")
  val LogicalNot: Token = Token("!")
  val MapOpen: Token = Token("{")
  val MapClose: Token = Token("}")
  val MemberDelimiter: Token = Token(",")
  val Multiplication: Token = Token("*")
  val NonEmpty: Token = Token("+")
  val ObjectOpen: Token = Token("{")
  val ObjectClose: Token = Token("}")
  val Optional: Token = Token("?")
  val PlaceholderOpenTilde: Token = Token("~{")
  val PlaceholderOpenDollar: Token = Token("${")
  val PlaceholderClose: Token = Token("}")
  val QuoteOpen: Token = Token("\"")
  val QuoteClose: Token = Token("\"")
  val Remainder: Token = Token("%")
  val SepOption: Token = Token("sep=")
  val Subtraction: Token = Token("-")
  val TrueOption: Token = Token("true=")
  val TypeParamOpen: Token = Token("[")
  val TypeParamClose: Token = Token("]")
  val TypeParamDelimiter: Token = Token(",")
  val UnaryMinus: Token = Token("-")
  val UnaryPlus: Token = Token("+")

  val tokenPairs = Map(
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

case class StringLiteral(value: Any) extends Atom {
  override def toString: String = {
    s"${'"'}${value}${'"'}"
  }

  override def length: Int = {
    toString.length
  }
}

/**
  * A sequence of adjacent atoms (with no spacing or wrapping)
  * @param atoms the atoms
  */
case class Adjacent(atoms: Seq[Atom]) extends Atom {
  override def toString: String = {
    atoms.mkString("")
  }

  override def length: Int = {
    atoms.map(_.length).sum
  }
}

/**
  * A sequence of atoms separated by a space
  * @param atoms the atoms
  */
case class Spaced(atoms: Seq[Atom], wrapping: Wrapping = Wrapping.Never) extends Atom {
  override def toString: String = {
    atoms.mkString(" ")
  }

  override def length: Int = {
    atoms.map(_.length).sum + atoms.length - 1
  }

  override def format(lineFormatter: LineFormatter): Unit = {
    lineFormatter.appendAll(atoms, wrapping = wrapping)
  }
}
