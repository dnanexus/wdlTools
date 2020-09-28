package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.WdlVersion
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}

class MetaMap(values: Map[String, TAT.MetaValue]) {
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
class Meta(kvs: Map[String, TAT.MetaValue], userDefaultValues: Map[String, WdlValues.V])
    extends MetaMap(kvs) {
  val defaults: Map[String, WdlValues.V] = Map.empty

  def contains(id: String): Boolean = kvs.contains(id)

  override def get(id: String, wdlTypes: Vector[WdlTypes.T] = Vector.empty): Option[WdlValues.V] = {
    super
      .get(id, wdlTypes)
      .orElse(userDefaultValues.get(id))
      .orElse(defaults.get(id))
  }
}

case class Draft2Meta(meta: Map[String, TAT.MetaValue] = Map.empty,
                      userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(meta, userDefaultValues) {
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

case class V1Meta(meta: Map[String, TAT.MetaValue] = Map.empty,
                  userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(meta, userDefaultValues)

object Meta {
  def create(version: WdlVersion,
             meta: Option[TAT.MetaSection],
             userDefaultValues: Map[String, WdlValues.V] = Map.empty): Meta = {
    val kvs = meta.map(_.kvs).getOrElse(Map.empty)
    version match {
      case WdlVersion.Draft_2 => Draft2Meta(kvs, userDefaultValues)
      case _                  => V1Meta(kvs, userDefaultValues)
    }
  }
}

case class Hints(hints: Option[TAT.MetaSection],
                 userDefaultValues: Map[String, WdlValues.V] = Map.empty)
    extends Meta(hints.map(_.kvs).getOrElse(Map.empty), userDefaultValues) {
  override val defaults: Map[String, WdlValues.V] = Map(
      Hints.ShortTaskKey -> V_Boolean(false),
      Hints.LocalizationOptionalKey -> V_Boolean(false),
      Hints.InputsKey -> V_Object(Map.empty),
      Hints.OutputsKey -> V_Object(Map.empty)
  )

  override protected def applyKv(id: String,
                                 value: TAT.MetaValue,
                                 wdlTypes: Vector[WdlTypes.T] = Vector.empty): WdlValues.V = {
    id match {
      case Hints.MaxCpuKey =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Float)))
      case Hints.MaxMemoryKey =>
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
                s"Invalid ${Hints.MaxMemoryKey} value ${other}"
            )
        }
      case Hints.ShortTaskKey =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.LocalizationOptionalKey =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Boolean)))
      case Hints.InputsKey | Hints.OutputsKey =>
        super.applyKv(id, value, Utils.checkTypes(id, wdlTypes, Vector(WdlTypes.T_Object)))
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

  def create(hints: Option[TAT.MetaSection],
             userDefaultValues: Map[String, WdlValues.V] = Map.empty): Hints =
    Hints(hints, userDefaultValues)
}
