package wdlTools.types

import wdlTools.syntax.WdlVersion
import wdlTools.types.WdlTypes._

/**
  * Restrict values of certain sections (runtime, hints) to specific types.
  * for each section, a key can be mapped to a vector of legal types. When
  * performing type inference, the actual value is attempted to be coerced
  * to each legal type, in order, until one coercion succeeds.
  */
case class TypeRestrictions(runtime: Map[String, Vector[T]] = Map.empty) {
  def merge(other: TypeRestrictions): TypeRestrictions = {
    def mergeMap(left: Map[String, Vector[T]],
                 right: Map[String, Vector[T]]): Map[String, Vector[T]] = {
      if (left.keySet.intersect(right.keySet).nonEmpty) {
        throw new RuntimeException(s"Collision between keys in ${this} and ${other}")
      }
      left ++ right
    }
    TypeRestrictions(runtime = mergeMap(runtime, other.runtime))
  }
}

object TypeRestrictions {
  def getDefaultRestrictions(version: WdlVersion): TypeRestrictions = {
    TypeRestrictions(
        runtime = getDefaultRuntimeRestrictions(version)
    )
  }

  private def getDefaultRuntimeRestrictions(version: WdlVersion): Map[String, Vector[T]] = {
    version match {
      case WdlVersion.V2 =>
        Map(
            "container" -> Vector(T_Array(T_String, nonEmpty = true), T_String),
            "memory" -> Vector(T_Int, T_String),
            "cpu" -> Vector(T_Float, T_Int),
            "gpu" -> Vector(T_Boolean),
            "disks" -> Vector(T_Int, T_Array(T_String, nonEmpty = true), T_String),
            "maxRetries" -> Vector(T_Int),
            "returnCodes" -> Vector(T_Array(T_Int, nonEmpty = true), T_Int, T_String)
        )
      case _ =>
        Map(
            "docker" -> Vector(T_Array(T_String, nonEmpty = true), T_String),
            "memory" -> Vector(T_Int, T_String)
        )
    }
  }
}
