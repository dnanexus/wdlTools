package wdlTools.util

final class DuplicateBindingException(message: String) extends Exception(message)

trait Bindings[T] {
  def contains(name: String): Boolean

  def keySet: Set[String]

  def add(name: String, value: T): Bindings[T]

  def update(bindings: Map[String, T]): Bindings[T]

  def toMap: Map[String, T]

  def update(bindings: Bindings[T]): Bindings[T] = {
    update(bindings.toMap)
  }

  def apply(name: String): T

  def get(name: String): Option[T]

  def intersect(names: Set[String]): Bindings[T]
}

case class MapBindings[T](all: Map[String, T] = Map.empty[String, T],
                          elementType: String = "binding")
    extends Bindings[T] {
  def contains(name: String): Boolean = all.contains(name)

  def keySet: Set[String] = all.keySet

  def add(name: String, value: T): MapBindings[T] = {
    if (contains(name)) {
      throw new DuplicateBindingException(
          s"${elementType} ${name} shadows an existing variable"
      )
    }
    this.copy(all = all + (name -> value))
  }

  def update(bindings: Map[String, T]): MapBindings[T] = {
    (keySet & bindings.keySet).toVector match {
      case Vector(name) =>
        throw new DuplicateBindingException(
            s"${elementType} ${name} shadows an existing variable"
        )
      case v if v.size > 1 =>
        throw new DuplicateBindingException(
            s"${elementType}s ${v.mkString(",")} shadow existing variables"
        )
      case _ => ()
    }
    this.copy(all ++ bindings)
  }

  def toMap: Map[String, T] = all

  def apply(name: String): T = all(name)

  def get(name: String): Option[T] = all.get(name)

  def intersect(names: Set[String]): Bindings[T] = {
    MapBindings((keySet & names).map(name => name -> all(name)).toMap)
  }
}

object Bindings {
  def empty[T]: Bindings[T] = MapBindings[T]()
}
