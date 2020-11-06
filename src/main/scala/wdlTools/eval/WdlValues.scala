package wdlTools.eval

// This is the WDL typesystem
object WdlValues {
  // any WDL value
  sealed trait V

  // primitive values
  case object V_Null extends V
  case class V_Boolean(value: Boolean) extends V
  case class V_String(value: String) extends V

  sealed trait V_Numeric extends V {
    def intValue: Long
    def floatValue: Double
  }
  case class V_Int(value: Long) extends V_Numeric {
    def intValue: Long = value
    lazy val floatValue: Double = value.toDouble
  }
  case class V_Float(value: Double) extends V_Numeric {
    def intValue: Long = value.toLong
    lazy val floatValue: Double = value
  }

  sealed trait V_Path extends V {
    def value: String
  }
  case class V_File(value: String) extends V_Path
  case class V_Directory(value: String) extends V_Path

  // compound values
  case class V_Pair(l: V, r: V) extends V
  case class V_Array(value: Vector[V]) extends V
  case class V_Map(value: Map[V, V]) extends V
  case class V_Struct(name: String, members: Map[String, V]) extends V
  case class V_Object(members: Map[String, V]) extends V

  // wrapper around another value to indicate it is associated with an
  // optional type
  case class V_Optional(value: V) extends V

  // An Archive is a special type of value that represents a TAR
  // archive that contains a serialized complex value, along with
  // any nested files contained by the value.
  case class V_Archive(path: String) extends V

  // results from calling a task or workflow
  case class V_Call(name: String, members: Map[String, V]) extends V
}
