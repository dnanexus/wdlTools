package wdlTools.util

import spray.json._

import scala.io.Source

object JsonUtil {
  def readJsonSource(src: Source, name: String): Map[String, JsValue] = {
    try {
      getFields(src.getLines.mkString(System.lineSeparator).parseJson)
    } catch {
      case _: NullPointerException =>
        throw new Exception(s"Could not open resource ${name}")
    }
  }

  def getFields(js: JsValue): Map[String, JsValue] = {
    js match {
      case JsObject(fields) => fields
      case other            => throw new Exception(s"Expected JsObject, got ${other}")
    }
  }

  def getValues(js: JsValue): Vector[JsValue] = {
    js match {
      case JsArray(values) => values
      case other           => throw new Exception(s"Expected JsArray, got ${other}")
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
