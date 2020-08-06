package wdlTools.eval

// This is the WDL typesystem
object WdlValues {
  // any WDL value
  sealed trait V
  sealed trait V_Numeric extends V {
    def intValue: Long
    def floatValue: Double
  }

  // primitive values
  case object V_Null extends V
  case class V_Boolean(value: Boolean) extends V
  case class V_Int(value: Long) extends V_Numeric {
    def intValue: Long = value
    lazy val floatValue: Double = value.toDouble
  }
  case class V_Float(value: Double) extends V_Numeric {
    def intValue: Long = value.toLong
    lazy val floatValue: Double = value
  }
  case class V_String(value: String) extends V
  case class V_File(value: String) extends V
  case class V_Directory(value: String) extends V

  // compound values
  case class V_Pair(l: V, r: V) extends V
  case class V_Array(value: Vector[V]) extends V
  case class V_Map(value: Map[V, V]) extends V
  case class V_Optional(value: V) extends V
  case class V_Struct(name: String, members: Map[String, V]) extends V
  case class V_Object(members: Map[String, V]) extends V

  // results from calling a task or workflow
  case class V_Call(name: String, members: Map[String, V]) extends V
}
