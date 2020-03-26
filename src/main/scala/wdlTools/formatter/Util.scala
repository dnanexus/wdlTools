package wdlTools.formatter

object Util {

  object BiMap {
    private[BiMap] trait MethodDistinctor
  }

  case class BiMap[X, Y](map: Map[X, Y]) {
    def this(tuples: (X, Y)*) = this(tuples.toMap)

    private val reverseMap = map.map(_.swap)
    require(map.size == reverseMap.size, "no 1 to 1 relation")

    def fromKey(x: X): Y = map(x)

    def fromValue(y: Y): X = reverseMap(y)
  }
}
