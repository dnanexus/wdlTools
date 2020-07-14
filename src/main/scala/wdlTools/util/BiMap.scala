package wdlTools.util

/**
  * Simple bi-directional Map class.
  * @param keys map keys
  * @param values map values - must be unique, i.e. you must be able to map values -> keys without collisions
  * @tparam X keys Type
  * @tparam Y values Type
  */
case class BiMap[X, Y](keys: Seq[X], values: Seq[Y]) {
  require(keys.size == values.size, "no 1 to 1 relation")
  private lazy val kvMap: Map[X, Y] = keys.zip(values).toMap
  private lazy val vkMap: Map[Y, X] = values.zip(keys).toMap

  def size: Int = keys.size

  def fromKey(x: X): Y = kvMap(x)

  def fromValue(y: Y): X = vkMap(y)

  def filterKeys(p: X => Boolean): BiMap[X, Y] = {
    BiMap.fromPairs(keys.zip(values).filter(item => p(item._1)))
  }
}

object BiMap {
  def empty[X, Y]: BiMap[X, Y] = fromMap(Map.empty)

  def fromPairs[X, Y](pairs: Seq[(X, Y)]): BiMap[X, Y] = {
    BiMap(pairs.map(_._1), pairs.map(_._2))
  }

  def fromMap[X, Y](map: Map[X, Y]): BiMap[X, Y] = {
    fromPairs(map.toVector)
  }
}

case class SymmetricBiMap[X](m: Map[X, X]) {
  private val bimap = m ++ m.map {
    case (k, v) => v -> k
  }

  def contains(key: X): Boolean = {
    bimap.contains(key)
  }

  def getOption(key: X): Option[X] = {
    bimap.get(key)
  }

  def get(key: X): X = {
    bimap(key)
  }
}

object SymmetricBiMap {
  def empty[X]: SymmetricBiMap[X] = SymmetricBiMap(Map.empty)
}
