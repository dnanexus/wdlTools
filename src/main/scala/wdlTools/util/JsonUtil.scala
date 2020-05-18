package wdlTools.util

import spray.json.{JsNumber, JsObject, JsString, JsValue}

object JsonUtil {
  def getFields(js: JsValue): Map[String, JsValue] = {
    js match {
      case JsObject(fields) => fields
      case other            => throw new Exception(s"Expected JsObject, got ${other}")
    }
  }

  def getString(js: JsValue): String = {
    js match {
      case JsString(value) => value
      case other           => throw new Exception(s"Expected JsString, got ${other}")
    }
  }

  def getInt(js: JsValue): Int = {
    js match {
      case JsNumber(value) => value.toInt
      case other           => throw new Exception(s"Expected JsNumber, got ${other}")
    }
  }
}
