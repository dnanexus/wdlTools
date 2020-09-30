package wdlTools.syntax

trait BuiltinSymbols {
  // operators
  val UnaryPlus: String = "+"
  val UnaryMinus: String = "-"
  val LogicalNot: String = "!"
  val LogicalOr: String = "||"
  val LogicalAnd: String = "&&"
  val Equality: String = "=="
  val Inequality: String = "!="
  val LessThan: String = "<"
  val LessThanOrEqual: String = "<="
  val GreaterThan: String = ">"
  val GreaterThanOrEqual: String = ">="
  val Addition: String = "+"
  val Subtraction: String = "-"
  val Multiplication: String = "*"
  val Division: String = "/"
  val Remainder: String = "%"

  // functions
  val AsMap = "as_map"
  val AsPairs = "as_pairs"
  val Basename = "basename"
  val Ceil = "ceil"
  val CollectByKey = "collect_by_key"
  val Cross = "cross"
  val Defined = "defined"
  val Flatten = "flatten"
  val Floor = "floor"
  val Glob = "glob"
  val Keys = "keys"
  val Length = "length"
  val Min = "min"
  val Max = "max"
  val Prefix = "prefix"
  val Quote = "quote"
  val Range = "range"
  val ReadBoolean = "read_boolean"
  val ReadFloat = "read_float"
  val ReadInt = "read_int"
  val ReadJson = "read_json"
  val ReadLines = "read_lines"
  val ReadMap = "read_map"
  val ReadObject = "read_object"
  val ReadObjects = "read_objects"
  val ReadString = "read_string"
  val ReadTsv = "read_tsv"
  val Round = "round"
  val SelectAll = "select_all"
  val SelectFirst = "select_first"
  val Sep = "sep"
  val Size = "size"
  val Squote = "squote"
  val Stderr = "stderr"
  val Stdout = "stdout"
  val Sub = "sub"
  val Suffix = "suffix"
  val Transpose = "transpose"
  val WriteJson = "write_json"
  val WriteLines = "write_lines"
  val WriteMap = "write_map"
  val WriteObject = "write_object"
  val WriteObjects = "write_objects"
  val WriteTsv = "write_tsv"
  val Zip = "zip"
}

object Builtins extends BuiltinSymbols

sealed abstract class Operator(val name: String, val symbol: String, val precedence: Int)
    extends Ordered[Operator] {
  def compare(that: Operator): Int = this.precedence - that.precedence
}

object Operator {
  case object UnaryPlus extends Operator("{plus}", Builtins.UnaryPlus, 8)
  case object UnaryMinus extends Operator("{minus}", Builtins.UnaryMinus, 8)
  case object LogicalNot extends Operator("{not}", Builtins.LogicalNot, 8)
  case object LogicalOr extends Operator("{or}", Builtins.LogicalOr, 2)
  case object LogicalAnd extends Operator("{and}", Builtins.LogicalAnd, 3)
  case object Equality extends Operator("{eq}", Builtins.Equality, 4)
  case object Inequality extends Operator("{neq}", Builtins.Inequality, 4)
  case object LessThan extends Operator("{lt}", Builtins.LessThan, 5)
  case object LessThanOrEqual extends Operator("{lte}", Builtins.LessThanOrEqual, 5)
  case object GreaterThan extends Operator("{gt}", Builtins.GreaterThan, 5)
  case object GreaterThanOrEqual extends Operator("{gte}", Builtins.GreaterThanOrEqual, 5)
  case object Addition extends Operator("{add}", Builtins.Addition, 6)
  case object Subtraction extends Operator("{sub}", Builtins.Subtraction, 6)
  case object Multiplication extends Operator("{mul}", Builtins.Multiplication, 7)
  case object Division extends Operator("{div}", Builtins.Division, 7)
  case object Remainder extends Operator("{mod}", Builtins.Remainder, 7)

  lazy val All: Map[String, Operator] = Set(
      UnaryPlus,
      UnaryMinus,
      LogicalNot,
      LogicalOr,
      LogicalAnd,
      Equality,
      Inequality,
      LessThan,
      LessThanOrEqual,
      GreaterThan,
      GreaterThanOrEqual,
      Addition,
      Subtraction,
      Multiplication,
      Division,
      Remainder
  ).map(o => o.name -> o).toMap
}
