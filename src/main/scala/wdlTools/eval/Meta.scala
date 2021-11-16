package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.WdlVersion
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}

class MetaMap(values: Map[String, TAT.MetaValue]) {
  protected def applyKv(id: String,
                        value: TAT.MetaValue,
                        wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    val wdlValue = value match {
      case TAT.MetaValueNull()       => V_Null
      case TAT.MetaValueString(s, _) => V_String(s)
      case TAT.MetaValueInt(i)       => V_Int(i)
      case TAT.MetaValueFloat(f)     => V_Float(f)
      case TAT.MetaValueBoolean(b)   => V_Boolean(b)
      case TAT.MetaValueArray(a)     => V_Array(a.map(x => applyKv(id, x)))
      case TAT.MetaValueObject(m) =>
        V_Object(m.map {
          case (k, v) => k -> applyKv(k, v)
        })
      case _ =>
        throw new EvalException(s"Unexpected value ${value} for key ${id}", value.loc)
    }
    if (wdlTypes.isEmpty) {
      wdlValue
    } else {
      Coercion.coerceToFirst(wdlTypes, wdlValue, value.loc)
    }
  }

  def get(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    values
      .get(id)
      .map(value => applyKv(id, value, wdlTypes))
  }
}

object MetaMap {
  def apply(kvs: Map[String, TAT.MetaValue]): MetaMap = new MetaMap(kvs)

  lazy val empty: MetaMap = MetaMap(Map.empty)
}

/**
  * Classes that translate MetaValues into WdlValues, for ease of inter-operability.
  * @param kvs mapping of meta key to value
  * @param userDefaultValues default values supplied by the users - these override any built-in defaults
  */
class Meta(kvs: Map[String, TAT.MetaValue],
           overrideValues: Option[VBindings] = None,
           userDefaultValues: Option[VBindings] = None)
    extends MetaMap(kvs) {
  val defaults: Map[String, WdlValues.V] = Map.empty

  def contains(id: String): Boolean = {
    overrideValues.exists(_.contains(id)) ||
    kvs.contains(id) ||
    userDefaultValues.exists(_.contains(id)) ||
    defaults.contains(id)
  }

  override def get(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    overrideValues
      .flatMap(_.get(id, wdlTypes))
      .orElse(super.get(id, wdlTypes))
      .orElse(userDefaultValues.flatMap(_.get(id, wdlTypes)))
      .orElse(defaults.get(id))
  }
}

case class Draft2Meta(meta: Map[String, TAT.MetaValue],
                      overrideValues: Option[VBindings] = None,
                      userDefaultValues: Option[VBindings] = None)
    extends Meta(meta, overrideValues, userDefaultValues) {
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

case class V1Meta(meta: Map[String, TAT.MetaValue],
                  overrideValues: Option[VBindings] = None,
                  userDefaultValues: Option[VBindings] = None)
    extends Meta(meta, overrideValues, userDefaultValues)

object Meta {
  def create(
      version: WdlVersion,
      meta: Option[TAT.MetaSection],
      overrideValues: Option[VBindings] = None,
      userDefaultValues: Option[VBindings] = None
  ): Meta = {
    val kvs = meta.map(_.kvs.toMap).getOrElse(Map.empty)
    version match {
      case WdlVersion.Draft_2 => Draft2Meta(kvs, overrideValues, userDefaultValues)
      case _                  => V1Meta(kvs, overrideValues, userDefaultValues)
    }
  }
}

case class Hints(hints: Option[TAT.MetaSection],
                 overrideValues: Option[VBindings] = None,
                 userDefaultValues: Option[VBindings] = None)
    extends Meta(hints.map(_.kvs).getOrElse(Map.empty), overrideValues, userDefaultValues) {
  override val defaults: Map[String, WdlValues.V] = Map(
      Hints.ShortTaskKey -> V_Boolean(false),
      Hints.LocalizationOptionalKey -> V_Boolean(false),
      Hints.InputsKey -> V_Object(),
      Hints.OutputsKey -> V_Object()
  )

  override protected def applyKv(id: String,
                                 value: TAT.MetaValue,
                                 wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    id match {
      case Hints.MaxCpuKey =>
        super.applyKv(id, value, EvalUtils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Float)))
      case Hints.MaxMemoryKey =>
        super.applyKv(
            id,
            value,
            EvalUtils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Int, WdlTypes.T_String))
        ) match {
          case i: V_Int => i
          case V_String(s) =>
            val d = EvalUtils.sizeStringToFloat(s, value.loc)
            V_Int(EvalUtils.floatToInt(d))
          case other =>
            throw new EvalException(
                s"Invalid ${Hints.MaxMemoryKey} value ${other}"
            )
        }
      case Hints.ShortTaskKey =>
        super.applyKv(id, value, EvalUtils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.LocalizationOptionalKey =>
        super.applyKv(id, value, EvalUtils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.InputsKey | Hints.OutputsKey =>
        super.applyKv(id, value, EvalUtils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Object)))
      case _ =>
        super.applyKv(id, value, wdlTypes)
    }
  }
}

object Hints {
  val MaxCpuKey = "maxCpu"
  val MaxMemoryKey = "maxMemory"
  val ShortTaskKey = "shortTask"
  val LocalizationOptionalKey = "localizationOptional"
  val InputsKey = "inputs"
  val OutputsKey = "outputs"

  def create(
      hints: Option[TAT.MetaSection],
      overrideValues: Option[VBindings] = None,
      userDefaultValues: Option[VBindings] = None
  ): Hints =
    Hints(hints, overrideValues, userDefaultValues)
}
