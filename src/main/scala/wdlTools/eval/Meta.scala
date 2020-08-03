package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.WdlVersion
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}

/**
  * Classes that translate MetaValues into WdlValues, for ease of inter-operability.
  * @param kvs mapping of meta key to value
  * @param userDefaultValues default values supplied by the users - these override any built-in defaults
  */
class Meta(kvs: Map[String, TAT.MetaValue], userDefaultValues: Map[String, WdlValues.V]) {
  val defaults: Map[String, WdlValues.V] = Map.empty

  protected def applyKv(id: String,
                        value: TAT.MetaValue,
                        wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    val wdlValue = value match {
      case TAT.MetaValueNull(_)       => V_Null
      case TAT.MetaValueString(s, _)  => V_String(s)
      case TAT.MetaValueInt(i, _)     => V_Int(i)
      case TAT.MetaValueFloat(f, _)   => V_Float(f)
      case TAT.MetaValueBoolean(b, _) => V_Boolean(b)
      case TAT.MetaValueArray(a, _)   => V_Array(a.map(x => applyKv(id, x)))
      case TAT.MetaValueObject(m, _) =>
        V_Object(m.map {
          case (k, v) => k -> applyKv(k, v)
        })
      case _ => throw new EvalException(s"Unexpected value ${value} for key ${id}", value.loc)
    }
    if (wdlTypes.isEmpty) {
      wdlValue
    } else {
      Coercion.coerceToFirst(wdlTypes, wdlValue, value.loc)
    }
  }

  lazy val values: Map[String, WdlValues.V] = kvs.map {
    case (id, value) => id -> applyKv(id, value)
  }

  def getValue(id: String): Option[WdlValues.V] = {
    values.get(id).orElse(userDefaultValues.get(id)).orElse(defaults.get(id))
  }
}

case class Draft2Meta(meta: Option[TAT.MetaSection],
                      userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(meta.map(_.kvs).getOrElse(Map.empty), userDefaultValues) {
  override protected def applyKv(id: String,
                                 value: TAT.MetaValue,
                                 wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    super.applyKv(id, value, wdlTypes) match {
      case s: V_String => s
      case _ =>
        throw new EvalException(s"Only string values are allowed in WDL draft-2 meta sections",
                                value.loc)
    }
  }
}

case class V1Meta(meta: Option[TAT.MetaSection],
                  userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(meta.map(_.kvs).getOrElse(Map.empty), userDefaultValues)

object Meta {
  def create(version: WdlVersion,
             meta: Option[TAT.MetaSection],
             userDefaultValues: Map[String, WdlValues.V] = Map.empty): Meta = {
    version match {
      case WdlVersion.Draft_2 => Draft2Meta(meta, userDefaultValues)
      case _                  => V1Meta(meta, userDefaultValues)
    }
  }
}

case class Hints(hints: Option[TAT.HintsSection],
                 userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(hints.map(_.kvs).getOrElse(Map.empty), userDefaultValues) {
  override val defaults: Map[String, WdlValues.V] = Map(
      Hints.Keys.ShortTask -> V_Boolean(false),
      Hints.Keys.LocalizationOptional -> V_Boolean(false),
      Hints.Keys.Inputs -> V_Object(Map.empty),
      Hints.Keys.Outputs -> V_Object(Map.empty)
  )

  override protected def applyKv(id: String,
                                 value: TAT.MetaValue,
                                 wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    id match {
      case Hints.Keys.MaxCpu =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Float)))
      case Hints.Keys.MaxMemory =>
        super.applyKv(
            id,
            value,
            Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Int, WdlTypes.T_String))
        ) match {
          case i: V_Int => i
          case V_String(s) =>
            val d = Utils.sizeStringToFloat(s, value.loc)
            V_Int(Utils.floatToInt(d))
          case other =>
            throw new EvalException(
                s"Invalid ${Hints.Keys.MaxMemory} value ${other}"
            )
        }
      case Hints.Keys.ShortTask =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.Keys.LocalizationOptional =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.Keys.Inputs | Hints.Keys.Outputs =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Object)))
      case _ =>
        super.applyKv(id, value, wdlTypes)
    }
  }
}

object Hints {
  object Keys {
    val MaxCpu = "maxCpu"
    val MaxMemory = "maxMemory"
    val ShortTask = "shortTask"
    val LocalizationOptional = "localizationOptional"
    val Inputs = "inputs"
    val Outputs = "outputs"
  }

  def create(hints: Option[TAT.HintsSection],
             userDefaultValues: Map[String, WdlValues.V] = Map.empty): Hints =
    Hints(hints, userDefaultValues)
}
