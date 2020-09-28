package wdlTools.util

final class DuplicateBindingException(message: String) extends Exception(message)

trait Bindings[K, T, +Self <: Bindings[K, T, Self]] {
  def contains(name: K): Boolean

  def keySet: Set[K]

  def toMap: Map[K, T]

  protected val elementType: String

  protected def copyFrom(values: Map[K, T]): Self

  def update(bindings: Map[K, T]): Self = {
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

  def update(bindings: Bindings[K, T, _]): Self = {
    update(bindings.toMap)
  }

  def add(key: K, value: T): Self = {
    update(Map(key -> value))
  }

  def apply(key: K): T

  def get(key: K): Option[T]

  def intersect(names: Set[K]): Self = {
    copyFrom((keySet & names).map(name => name -> apply(name)).toMap)
  }
}

abstract class AbstractBindings[K, T, +Self <: Bindings[K, T, Self]](
    all: Map[K, T] = Map.empty[K, T]
) extends Bindings[K, T, Self] {
  def contains(key: K): Boolean = all.contains(key)

  def keySet: Set[K] = all.keySet

  def toMap: Map[K, T] = all

  def apply(key: K): T = all(key)

  def get(key: K): Option[T] = all.get(key)
}

case class DefaultBindings[T](bindings: Map[String, T] = Map.empty,
                              override val elementType: String = "binding")
    extends AbstractBindings[String, T, DefaultBindings[T]](bindings) {
  override protected def copyFrom(values: Map[String, T]): DefaultBindings[T] = {
    copy(bindings = values)
  }
}
