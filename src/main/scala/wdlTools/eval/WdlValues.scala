package wdlTools.eval

// This is the WDL typesystem
object WdlValues {
  // any WDL value
  sealed trait WV

  // primitive types
  case class WV_String(value : String) extends WV
  case class WV_File(value : String)  extends WV
  case class WV_Boolean(value : Boolean) extends WV
  case class WV_Int(value : Int)  extends WV
  case class WV_Float(value : Float) extends WV
}
