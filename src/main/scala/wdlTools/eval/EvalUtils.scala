package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.SourceLocation
import wdlTools.types.WdlTypes
import dx.util.{AbstractBindings, Bindings}

import scala.annotation.tailrec

object EvalUtils {
  lazy val SizeUnits: Map[String, Double] = Map(
      "b" -> 1d,
      "kb" -> 1000d,
      "mb" -> 1000d * 1000d,
      "gb" -> 1000d * 1000d * 1000d,
      "tb" -> 1000d * 1000d * 1000d * 1000d,
      "kib" -> 1024d,
      "mib" -> 1024d * 1024d,
      "gib" -> 1024d * 1024d * 1024d,
      "tib" -> 1024d * 1024d * 1024d * 1024d
  )

  /**
    * Returns the multiplier for the given size unit.
    * @param sizeUnit units parameter (KB, KiB, MB, GiB, ...)
    * @param loc SourceLocation
    * @return
    */
  def getSizeMultiplier(sizeUnit: String, loc: SourceLocation): Double = {
    val key = sizeUnit.toLowerCase match {
      case k if !k.endsWith("b") => s"${k}b"
      case k                     => k
    }
    SizeUnits.getOrElse(key, throw new EvalException(s"Unknown unit ${sizeUnit}", loc))
  }

  private val stringSizeRegexp = "([\\d.]+)(?:\\s*(.+))?".r

  def sizeToFloat(size: Double, suffix: String, loc: SourceLocation): Double = {
    size * getSizeMultiplier(suffix, loc)
  }

  def sizeStringToFloat(sizeString: String,
                        loc: SourceLocation,
                        defaultSuffix: String = "b"): Double = {
    sizeString match {
      case stringSizeRegexp(d, u) if u == null => d.toDouble * getSizeMultiplier(defaultSuffix, loc)
      case stringSizeRegexp(d, u)              => d.toDouble * getSizeMultiplier(u, loc)
      case other                               => throw new EvalException(s"Invalid size string ${other}", loc)
    }
  }

  def floatToInt(d: Double): Long = {
    Math.ceil(d).toLong
  }

  def checkTypes(id: String,
                 userTypes: Vector[WdlTypes.T] = Vector.empty,
                 allowedTypes: Vector[WdlTypes.T] = Vector.empty): Vector[WdlTypes.T] = {
    if (userTypes.isEmpty) {
      allowedTypes
    } else {
      if (allowedTypes.nonEmpty) {
        val extraTypes = userTypes.toSet -- allowedTypes.toSet
        if (extraTypes.nonEmpty) {
          throw new RuntimeException(
              s"Type(s) ${extraTypes.mkString(",")} not allowed for Runtime key ${id}"
          )
        }
      }
      userTypes
    }
  }

  def isOptional(v: V): Boolean = {
    v match {
      case _: V_Optional => true
      case _             => false
    }
  }

  /**
    * Makes a value optional.
    * @param v the value
    * @param force if true, then `t` will be made optional even if it is already optional.
    * @return
    */
  def ensureOptional(v: V, force: Boolean = false): V_Optional = {
    v match {
      case v if force    => V_Optional(v)
      case v: V_Optional => v
      case _             => V_Optional(v)
    }
  }

  @tailrec
  def unwrapOptional(v: V, mustBeOptional: Boolean = false): V = {
    v match {
      case V_Optional(wrapped) => unwrapOptional(wrapped)
      case _ if mustBeOptional =>
        throw new Exception(s"Value ${v} is not V_Optional")
      case _ => v
    }
  }

  @tailrec
  def isPrimitive(value: V): Boolean = {
    value match {
      case V_Null         => true
      case V_Boolean(_)   => true
      case V_Int(_)       => true
      case V_Float(_)     => true
      case V_String(_)    => true
      case V_File(_)      => true
      case V_Directory(_) => true
      case V_Optional(v)  => isPrimitive(v)
      case _              => false
    }
  }

  // TODO: within string interpolation, V_Null should render as empty string
  @tailrec
  def formatPrimitive(value: V, loc: SourceLocation = SourceLocation.empty): String = {
    value match {
      case V_Null             => "null"
      case V_Boolean(value)   => value.toString
      case V_Int(value)       => value.toString
      case V_Float(value)     => value.toString
      case V_String(value)    => value
      case V_File(value)      => value
      case V_Directory(value) => value
      case V_Optional(x)      => formatPrimitive(x, loc)
      case other =>
        throw new EvalException(s"${other} is not a primitive value", loc)
    }
  }

  def prettyFormat(value: V): String = {
    value match {
      case V_Optional(value)       => prettyFormat(value)
      case _ if isPrimitive(value) => s"${formatPrimitive(value)}"
      case V_Pair(l, r)            => s"(${prettyFormat(l)}, ${prettyFormat(r)})"
      case V_Array(array)          => s"[${array.map(prettyFormat).mkString(", ")}]"
      case V_Map(members) =>
        val memberStr = members
          .map {
            case (k, v) => s"${prettyFormat(k)}: ${prettyFormat(v)}"
          }
          .mkString(", ")
        s"{${memberStr}}"
      case V_Object(members) =>
        val memberStr = members
          .map {
            case (k, v) => s"${k}: ${prettyFormat(v)}"
          }
          .mkString(", ")
        s"{${memberStr}}"
      case V_Struct(name, members) =>
        val memberStr = members
          .map {
            case (k, v) => s"${k}: ${prettyFormat(v)}"
          }
          .mkString(", ")
        s"${name} {${memberStr}}"
      case V_Call(name, members) =>
        val memberStr = members
          .map {
            case (k, v) => s"${k}: ${prettyFormat(v)}"
          }
          .mkString(", ")
        s"${name} { input: ${memberStr} }"
      case other => throw new Exception(s"unexpected value ${other}")
    }
  }

  def transform(value: V, transformer: V => Option[V]): V = {
    def inner(innerValue: V): V = {
      val v = transformer(innerValue)
      if (v.isDefined) {
        return v.get
      }
      innerValue match {
        case V_Optional(v) => V_Optional(inner(v))
        case V_Array(vec)  => V_Array(vec.map(inner))
        case V_Pair(l, r)  => V_Pair(inner(l), inner(r))
        case V_Map(members) =>
          V_Map(members.map {
            case (k, v) => inner(k) -> inner(v)
          })
        case V_Object(members) =>
          V_Object(members.map { case (k, v) => k -> inner(v) })
        case V_Struct(_, members) =>
          V_Object(members.map { case (k, v) => k -> inner(v) })
        case other => other
      }
    }
    inner(value)
  }
}

trait VBindings extends Bindings[String, V] {
  val allowNonstandardCoercions: Boolean

  def get(id: String,
          wdlTypes: Vector[WdlTypes.T] = Vector.empty,
          sourceLocation: SourceLocation = SourceLocation.empty): Option[WdlValues.V] = {
    val value = get(id)
    if (wdlTypes.nonEmpty) {
      value.map(value =>
        Coercion.coerceToFirst(wdlTypes, value, sourceLocation, allowNonstandardCoercions)
      )
    } else {
      value
    }
  }
}

case class WdlValueBindings(bindings: Map[String, V] = Map.empty,
                            allowNonstandardCoercions: Boolean = false,
                            override val elementType: String = "value")
    extends AbstractBindings[String, V](bindings)
    with VBindings {
  override protected def copyFrom(values: Map[String, V]): WdlValueBindings = {
    copy(bindings = values)
  }
}

object WdlValueBindings {
  lazy val empty: WdlValueBindings = WdlValueBindings()
}
