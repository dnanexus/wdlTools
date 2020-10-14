package wdlTools.util

import spray.json.{DeserializationException, JsString, JsValue, RootJsonFormat}

import scala.collection.immutable.{ListMap, SortedSet}

abstract class Enum extends Enumeration {
  def names: SortedSet[String] = SortedSet.from(values.map(_.toString))

  protected lazy val nameToValue: ListMap[String, Value] = {
    ListMap.from(values.map(x => x.toString -> x))
  }

  private lazy val byLowerCaseName: Map[String, Value] = nameToValue.map {
    case (key, value) => key.toLowerCase -> value
  }

  def hasName(name: String): Boolean = nameToValue.contains(name)

  def hasNameIgnoreCase(name: String): Boolean = byLowerCaseName.contains(name.toLowerCase)

  def withNameIgnoreCase(name: String): Value = {
    byLowerCaseName.getOrElse(name.toLowerCase,
                              throw new NoSuchElementException(s"No value found for '$name'"))
  }
}

object Enum {
  implicit def enumFormat[T <: Enum](implicit enu: T): RootJsonFormat[T#Value] = {
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

  def min[V <: Ordered[V]](x: V, y: V): V = {
    if (x <= y) {
      x
    } else {
      y
    }
  }

  def max[V <: Ordered[V]](x: V, y: V): V = {
    if (x >= y) {
      x
    } else {
      y
    }
  }
}
