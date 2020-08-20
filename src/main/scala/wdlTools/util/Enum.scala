package wdlTools.util

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
