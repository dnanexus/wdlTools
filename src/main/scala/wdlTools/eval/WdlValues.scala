package wdlTools.eval

import scala.collection.immutable.SeqMap

// This is the WDL typesystem
object WdlValues {
  // any WDL value
  sealed trait V

  // primitive values
  case object V_Null extends V
  case object V_ForcedNull extends V
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

  // collection values
  sealed trait V_Collection extends V
  case class V_Pair(l: V, r: V) extends V_Collection

  case class V_Array(items: Vector[V]) extends V_Collection

  object V_Array {
    def apply(items: V*): V_Array = {
      V_Array(items.toVector)
    }
  }

  case class V_Map(items: SeqMap[V, V]) extends V_Collection

  object V_Map {
    def apply(items: (V, V)*): V_Map = {
      V_Map(items.to(SeqMap))
    }
  }

  case class V_Struct(name: String, fields: SeqMap[String, V]) extends V_Collection

  object V_Struct {
    def apply(name: String, fields: (String, V)*): V_Struct = {
      V_Struct(name, fields.to(SeqMap))
    }
  }

  case class V_Object(fields: SeqMap[String, V]) extends V_Collection

  object V_Object {
    def apply(fields: (String, V)*): V_Object = {
      V_Object(fields.to(SeqMap))
    }
  }

  // wrapper around another value to indicate it is associated with an
  // optional type
  case class V_Optional(value: V) extends V

  // results from calling a task or workflow
  case class V_Call(name: String, outputs: SeqMap[String, V]) extends V

  object V_Call {
    def apply(name: String, outputs: (String, V)*): V_Call = {
      V_Call(name, outputs.to(SeqMap))
    }
  }
}
