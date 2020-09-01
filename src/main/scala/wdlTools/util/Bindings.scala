package wdlTools.util

final class DuplicateBindingException(message: String) extends Exception(message)

trait Bindings[T, +Self <: Bindings[T, Self]] {
  def contains(name: String): Boolean

  def keySet: Set[String]

  def toMap: Map[String, T]

  protected val elementType: String

  protected def copyFrom(values: Map[String, T]): Self

  def update(bindings: Map[String, T]): Self = {
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
    copyFrom(toMap ++ bindings)
  }

  def update(bindings: Bindings[T, _]): Self = {
    update(bindings.toMap)
  }

  def add(name: String, value: T): Self = {
    update(Map(name -> value))
  }

  def apply(name: String): T

  def get(name: String): Option[T]

  def intersect(names: Set[String]): Self = {
    copyFrom((keySet & names).map(name => name -> apply(name)).toMap)
  }
}

abstract class AbstractBindings[T, +Self <: Bindings[T, Self]](
    all: Map[String, T] = Map.empty[String, T]
) extends Bindings[T, Self] {
  def contains(name: String): Boolean = all.contains(name)

  def keySet: Set[String] = all.keySet

  def toMap: Map[String, T] = all

  def apply(name: String): T = all(name)

  def get(name: String): Option[T] = all.get(name)
}

case class DefaultBindings[T](bindings: Map[String, T] = Map.empty,
                              override val elementType: String = "binding")
    extends AbstractBindings[T, DefaultBindings[T]](bindings) {
  override protected def copyFrom(values: Map[String, T]): DefaultBindings[T] = {
    copy(bindings = values)
  }
}
