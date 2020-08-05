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

object Builtins extends BuiltinSymbols {
  lazy val AllOperators: Set[String] = Set(
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
  )
}
