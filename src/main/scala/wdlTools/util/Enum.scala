package wdlTools.util

import spray.json.{DeserializationException, JsString, JsValue, RootJsonFormat}

import scala.collection.SortedSet

abstract class Enum extends Enumeration {
  private lazy val byLowerCaseName: Map[String, Value] =
    values.map(x => x.toString.toLowerCase -> x).toMap

  def names: SortedSet[String] = values.map(_.toString)

  def withNameIgnoreCase(name: String): Value = {
    byLowerCaseName.getOrElse(name.toLowerCase,
                              throw new NoSuchElementException(s"No value found for '$name'"))
  }
}

object Enum {
  implicit def enumFormat[T <: Enum](implicit enu: T): RootJsonFormat[T#Value] =
    new RootJsonFormat[T#Value] {
      def write(obj: T#Value): JsValue = {
        JsString(obj.toString)
      }

      def read(jsv: JsValue): T#Value = {
        jsv match {
          case JsString(txt) =>
            enu.withNameIgnoreCase(txt)
          case somethingElse =>
            throw DeserializationException(
                s"Expected a value from Enum $enu instead of $somethingElse"
            )
        }
      }
    }
}
