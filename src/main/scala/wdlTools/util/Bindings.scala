package wdlTools.util

final class DuplicateBindingException(message: String) extends Exception(message)

case class Bindings[T](all: Map[String, T] = Map.empty[String, T],
                       elementType: String = "binding") {
  def add(name: String, value: T): Bindings[T] = {
    if (contains(name)) {
      throw new DuplicateBindingException(
          s"${elementType} ${name} shadows an existing variable"
      )
    }
    this.copy(all = all + (name -> value))
  }
  def update(bindings: Map[String, T]): Bindings[T] = {
    (getNames & bindings.keySet).toVector match {
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

  def update(bindings: Bindings[T]): Bindings[T] = {
    update(bindings.all)
  }

  def contains(name: String): Boolean = all.contains(name)

  def apply(name: String): T = all(name)

  def get(name: String): Option[T] = all.get(name)

  def intersect(names: Set[String]): Bindings[T] = {
    Bindings((getNames & names).map(name => name -> all(name)).toMap)
  }

  def getNames: Set[String] = all.keySet
}

object Bindings {
  def empty[T]: Bindings[T] = Bindings[T]()
}
