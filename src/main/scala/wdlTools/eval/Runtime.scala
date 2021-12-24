package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.types.WdlTypes._
import dx.util.{Bindings, SymmetricBiMap}

case class DiskRequest(size: Long,
                       mountPoint: Option[String] = None,
                       diskType: Option[String] = None)

/**
  * Evaluation of runtime section. For any given runtime attribute, the value is
  * resolved in the following order:
  * overrides > runtime section > user defaults > spec defaults
  */
abstract class Runtime(runtime: Map[String, TAT.Expr],
                       overrideValues: Option[VBindings],
                       userDefaultValues: Option[VBindings],
                       runtimeLocation: SourceLocation) {
  private var cache: Map[String, Option[V]] = Map.empty

  val defaults: Map[String, V]

  val aliases: SymmetricBiMap[String] = SymmetricBiMap.empty

  def allows(id: String): Boolean

  def contains(id: String, followAlias: Boolean = true): Boolean = {
    allows(id) && (
        cache.contains(id) ||
        overrideValues.exists(_.contains(id)) ||
        runtime.contains(id) ||
        (followAlias && aliases.contains(id) && contains(aliases.get(id), followAlias = false)) ||
        userDefaultValues.exists(_.contains(id)) ||
        defaults.contains(id)
    )
  }

  protected def applyKv(id: String, expr: TAT.Expr, wdlType: Vector[T] = Vector.empty): V

  def get(id: String,
          wdlTypes: Vector[T] = Vector.empty,
          followAlias: Boolean = true): Option[V] = {
    cache.getOrElse(
        id, {
          // TODO: check type for overrides and user defaults
          val value = overrideValues.flatMap(_.get(id)).orElse {
            runtime.get(id) match {
              case Some(expr) => Some(applyKv(id, expr, wdlTypes))
              case None if followAlias && aliases.contains(id) =>
                get(aliases.get(id), wdlTypes, followAlias = false)
              case None => userDefaultValues.flatMap(_.get(id, wdlTypes)).orElse(defaults.get(id))
            }
          }
          cache += (id -> value)
          value
        }
    )
  }

  def getAll: Map[String, WdlValues.V] = {
    runtime.keys.map(key => key -> get(key).get).toMap
  }

  def getSourceLocation(id: String, followAlias: Boolean = true): SourceLocation = {
    runtime.get(id) match {
      case Some(v) => v.loc
      case None if followAlias && aliases.contains(id) =>
        getSourceLocation(aliases.get(id), followAlias = false)
      case None => runtimeLocation
    }
  }

  def container: Vector[String]

  def cpu: Double

  // allow fractional values - round up to nearest int
  def memory: Long

  def disks: Vector[DiskRequest]

  /**
    * Return codes that indicate success. If None, then all return codes
    * indicate success.
    */
  def returnCodes: Option[Set[Int]] = {
    val loc = getSourceLocation(Runtime.ReturnCodesKey)
    get(Runtime.ReturnCodesKey, Vector(T_Int, T_Array(T_Int), T_String)) match {
      case None                => Some(Runtime.ReturnCodesDefault)
      case Some(V_String("*")) => None
      case Some(V_Int(i))      => Some(Set(i.toInt))
      case Some(V_Array(v)) =>
        Some(v.map {
          case V_Int(i) => i.toInt
          case other =>
            throw new EvalException(
                s"Invalid ${Runtime.ReturnCodesKey} array item value ${other}",
                loc
            )
        }.toSet)
      case other =>
        throw new EvalException(s"Invalid ${Runtime.ReturnCodesKey} value ${other}", loc)
    }
  }

  def isValidReturnCode(returnCode: Int): Boolean = {
    returnCodes.forall(_.contains(returnCode))
  }
}

case class DefaultRuntime(runtime: Option[TAT.RuntimeSection],
                          evaluator: Eval,
                          wdlVersion: WdlVersion,
                          ctx: Option[Bindings[String, V]],
                          overrideValues: Option[VBindings] = None,
                          userDefaultValues: Option[VBindings],
                          runtimeLocation: SourceLocation)
    extends Runtime(runtime.map(_.kvs).getOrElse(Map.empty),
                    overrideValues,
                    userDefaultValues,
                    runtimeLocation) {
  assert(wdlVersion < WdlVersion.V2, "DefaultRuntime is only for WDL versions < 2")
  val defaults: Map[String, V] = Map.empty

  override val aliases: SymmetricBiMap[String] = {
    if (wdlVersion >= WdlVersion.V1_1) {
      SymmetricBiMap(Map(Runtime.DockerKey -> Runtime.ContainerKey))
    } else {
      SymmetricBiMap.empty
    }
  }

  // prior to WDL 2, any key was allowed
  override def allows(key: String): Boolean = true

  override protected def applyKv(id: String,
                                 expr: TAT.Expr,
                                 wdlType: Vector[T] = Vector.empty): V = {
    (ctx, wdlType) match {
      case (Some(c), Vector()) => evaluator.applyExpr(expr, c)
      case (Some(c), v)        => evaluator.applyExprAndCoerce(expr, v, c)
      case (None, Vector())    => evaluator.applyConst(expr)
      case (None, v)           => evaluator.applyConstAndCoerce(expr, v)
    }
  }

  lazy val container: Vector[String] = {
    get(Runtime.DockerKey, Vector(T_String, T_Array(T_String))) match {
      case None              => Vector.empty
      case Some(V_String(s)) => Vector(s)
      case Some(V_Array(a)) =>
        a.map {
          case V_String(s) => s
          case other =>
            throw new EvalException(s"Invalid ${Runtime.DockerKey} array item value ${other}",
                                    getSourceLocation(Runtime.DockerKey))
        }
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.DockerKey} value ${other}",
            getSourceLocation(Runtime.DockerKey)
        )
    }
  }

  override def cpu: Double = {
    get(Runtime.CpuKey, Vector(T_Int, T_Float, T_String)) match {
      case Some(V_Int(i))    => i.toDouble
      case Some(V_Float(f))  => f
      case Some(V_String(s)) => s.toDouble
      case None              => Runtime.CpuDefault
      case other =>
        throw new EvalException(s"Invalid ${Runtime.CpuKey} value ${other}",
                                getSourceLocation(Runtime.CpuKey))
    }
  }

  lazy val memory: Long = {
    get(Runtime.MemoryKey, Vector(T_Int, T_Float, T_String)) match {
      case Some(V_Int(i))   => i
      case Some(V_Float(d)) => EvalUtils.floatToInt(d)
      case Some(V_String(s)) =>
        val d = EvalUtils.sizeStringToFloat(s, getSourceLocation(Runtime.MemoryKey))
        EvalUtils.floatToInt(d)
      case None => Runtime.MemoryDefault
      case other =>
        throw new EvalException(s"Invalid ${Runtime.MemoryKey} value ${other}",
                                getSourceLocation(Runtime.MemoryKey))
    }
  }

  lazy val disks: Vector[DiskRequest] = {
    get(
        Runtime.DisksKey,
        Vector(T_Int, T_Float, T_String, T_Array(T_Int), T_Array(T_Float), T_Array(T_String))
    ) match {
      case None    => Runtime.parseDisks(V_String(Runtime.DisksDefault))
      case Some(v) => Runtime.parseDisks(v, loc = getSourceLocation(Runtime.DisksKey))
    }
  }
}

case class V2Runtime(runtime: Option[TAT.RuntimeSection],
                     evaluator: Eval,
                     wdlVersion: WdlVersion,
                     ctx: Option[Bindings[String, V]],
                     overrideValues: Option[VBindings] = None,
                     userDefaultValues: Option[VBindings],
                     runtimeLocation: SourceLocation)
    extends Runtime(runtime.map(_.kvs).getOrElse(Map.empty),
                    overrideValues,
                    userDefaultValues,
                    runtimeLocation) {
  assert(wdlVersion >= WdlVersion.V2, "V2Runtime only supports WDL versions >= 2")

  val defaults: Map[String, V] = Map(
      Runtime.CpuKey -> V_Float(Runtime.CpuDefault),
      Runtime.MemoryKey -> V_Int(Runtime.MemoryDefault),
      Runtime.GpuKey -> V_Boolean(Runtime.GpuDefault),
      Runtime.DisksKey -> V_String(Runtime.DisksDefault),
      Runtime.MaxRetriesKey -> V_Int(Runtime.MaxRetriesDefault),
      Runtime.ReturnCodesKey -> (if (Runtime.ReturnCodesDefault.size == 1) {
                                   V_Int(Runtime.ReturnCodesDefault.head)
                                 } else {
                                   V_Array(Runtime.ReturnCodesDefault.map(V_Int(_)).toVector)
                                 })
  )

  private val allowedKeys: Set[String] = Set(
      Runtime.ContainerKey,
      Runtime.CpuKey,
      Runtime.MemoryKey,
      Runtime.DisksKey,
      Runtime.GpuKey,
      Runtime.MaxRetriesKey,
      Runtime.ReturnCodesKey
  )

  override def allows(key: String): Boolean = {
    allowedKeys.contains(key)
  }

  override val aliases: SymmetricBiMap[String] =
    SymmetricBiMap(Map(Runtime.DockerKey -> Runtime.ContainerKey))

  private def intersectTypes(t1: Vector[T], t2: Vector[T]): Vector[T] = {
    (t1, t2) match {
      case (Vector(), v) => v
      case (v, Vector()) => v
      case (v1, v2) =>
        val intersection = (v1.toSet & v2.toSet).toVector
        if (intersection.isEmpty) {
          throw new EvalException(s"incompatible types ${t1} does not intersect ${t2}")
        }
        intersection
    }
  }

  protected def applyKv(id: String, expr: TAT.Expr, wdlType: Vector[T] = Vector.empty): V = {
    def getValue(allowed: Vector[T]): V = {
      (ctx, wdlType) match {
        case (Some(c), Vector()) =>
          evaluator.applyExprAndCoerce(expr, allowed, c)
        case (Some(c), v) =>
          evaluator.applyExprAndCoerce(expr, intersectTypes(allowed, v), c)
        case (None, Vector()) =>
          evaluator.applyConstAndCoerce(expr, allowed)
        case (None, v) =>
          evaluator.applyConstAndCoerce(expr, intersectTypes(allowed, v))
      }
    }

    id match {
      case Runtime.ContainerKey =>
        getValue(Vector(T_String, T_Array(T_String)))
      case Runtime.CpuKey =>
        // always return cpu as a float
        getValue(Vector(T_Float))
      case Runtime.MemoryKey =>
        // always return memory in bytes (round up to nearest byte)
        getValue(Vector(T_Int, T_String)) match {
          case i: V_Int   => i
          case V_Float(d) => V_Int(EvalUtils.floatToInt(d))
          case V_String(s) =>
            val d = EvalUtils.sizeStringToFloat(s, expr.loc)
            V_Int(EvalUtils.floatToInt(d))
          case other =>
            throw new EvalException(s"Invalid ${Runtime.MemoryKey} value ${other}",
                                    getSourceLocation(Runtime.MemoryKey))
        }
      case Runtime.DisksKey =>
        getValue(Vector(T_Int, T_Array(T_String), T_String)) match {
          case i: V_Int   => V_String(s"${i} GiB")
          case V_Float(d) => V_String(s"${d} GiB")
          case v          => v
        }
      case Runtime.GpuKey =>
        getValue(Vector(T_Boolean))
      case Runtime.MaxRetriesKey =>
        getValue(Vector(T_Int))
      case Runtime.ReturnCodesKey =>
        getValue(Vector(T_Int, T_Array(T_Int), T_String))
      case other =>
        throw new EvalException(s"Runtime key ${other} not allowed in WDL version 2.0+", expr.loc)
    }
  }

  lazy val container: Vector[String] = {
    get(Runtime.ContainerKey, Vector(T_String, T_Array(T_String))) match {
      case None              => Vector.empty
      case Some(V_String(s)) => Vector(s)
      case Some(V_Array(a)) =>
        a.map {
          case V_String(s) => s
          case other       => throw new EvalException(s"Invalid container array item value ${other}")
        }
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.ContainerKey} value ${other}",
            getSourceLocation(Runtime.ContainerKey)
        )
    }
  }

  override def cpu: Double = {
    get(Runtime.CpuKey, Vector(T_Float)) match {
      case Some(V_Float(f)) => f
      case None             => throw new RuntimeException("Missing default value for runtime.cpu")
      case other            => throw new EvalException(s"Invalid cpu value ${other}")
    }
  }

  lazy val memory: Long = {
    get(Runtime.MemoryKey, Vector(T_Int)) match {
      case Some(V_Int(i)) => i
      case None           => throw new RuntimeException("Missing default value for runtime.memory")
      case other          => throw new EvalException(s"Invalid memory value ${other}")
    }
  }

  lazy val disks: Vector[DiskRequest] = {
    val loc = getSourceLocation(Runtime.DisksKey)
    get(
        Runtime.DisksKey,
        Vector(T_Int, T_Float, T_String, T_Array(T_Int), T_Array(T_Float), T_Array(T_String))
    ) match {
      case Some(v) =>
        Runtime.parseDisks(v, loc = loc).map {
          case DiskRequest(_, _, Some(diskType)) =>
            throw new EvalException(
                s"In WDL 2.0, it is not allowed to define the disk type (${diskType}) in runtime.disks",
                loc
            )
          case other => other
        }
      case None =>
        throw new EvalException(s"No value for 'disks'", loc)
    }
  }
}

object Runtime {
  val ContainerKey = "container"
  val DockerKey = "docker"
  val CpuKey = "cpu"
  val CpuDefault = 1.0
  val MemoryKey = "memory"
  lazy val MemoryDefault: Long = {
    val d = EvalUtils.sizeStringToFloat("2 GiB", SourceLocation.empty)
    EvalUtils.floatToInt(d)
  }
  val DisksKey = "disks"
  val DisksDefault = "1 GiB"
  val GpuKey = "gpu"
  val GpuDefault = false
  val MaxRetriesKey = "maxRetries"
  val MaxRetriesDefault = 0
  val ReturnCodesKey = "returnCodes"
  val ReturnCodesDefault = Set(0)

  def fromTask(
      task: TAT.Task,
      evaluator: Eval,
      ctx: Option[Bindings[String, V]] = None,
      overrideValues: Option[VBindings] = None,
      defaultValues: Option[VBindings] = None
  ): Runtime = {
    create(task.runtime,
           evaluator,
           ctx,
           overrideValues,
           defaultValues,
           Some(task.runtime.map(_.loc).getOrElse(task.loc)))
  }

  def create(
      runtime: Option[TAT.RuntimeSection],
      evaluator: Eval,
      ctx: Option[Bindings[String, V]] = None,
      overrideValues: Option[VBindings] = None,
      defaultValues: Option[VBindings] = None,
      runtimeLocation: Option[SourceLocation] = None
  ): Runtime = {
    val loc = runtime
      .map(_.loc)
      .orElse(runtimeLocation)
      .getOrElse(
          throw new RuntimeException("either 'runtime' or 'runtimeLocation' must be nonEmpty")
      )
    evaluator.wdlVersion match {
      case None => throw new RuntimeException("no WdlVersion")
      case Some(wdlVersion: WdlVersion) if wdlVersion >= WdlVersion.V2 =>
        V2Runtime(runtime, evaluator, wdlVersion, ctx, overrideValues, defaultValues, loc)
      case Some(wdlVersion) =>
        DefaultRuntime(runtime, evaluator, wdlVersion, ctx, overrideValues, defaultValues, loc)
    }
  }

  /**
    * Parses the value of a runtime `disks` key.
    * @param defaultMountPoint default mount point (defaults to None)
    * @param defaultDiskType default disk type (defaults to None)
    * @return a Vector of tuple (disk size, mount point, disk type), where disk size is the (integral) size in bytes,
    *         mount point is the mount point path (or None to mount at the execution directory), and disk
    *         type is the type of disk to allocate (typically 'SSD' or 'HDD', although in WDL 2.0+ it is not
    *         allowed to specify the disk type).
    */
  def parseDisks(
      value: V,
      defaultMountPoint: Option[String] = None,
      defaultDiskType: Option[String] = None,
      loc: SourceLocation = SourceLocation.empty
  ): Vector[DiskRequest] = {
    value match {
      case V_Int(i) =>
        val bytes = EvalUtils.floatToInt(EvalUtils.sizeToFloat(i.toDouble, "GiB", loc))
        Vector(DiskRequest(bytes, defaultMountPoint, defaultDiskType))
      case V_Float(d) =>
        val bytes = EvalUtils.floatToInt(EvalUtils.sizeToFloat(d, "GiB", loc))
        Vector(DiskRequest(bytes, defaultMountPoint, defaultDiskType))
      case V_Array(a) =>
        a.flatMap(v => parseDisks(v, defaultMountPoint, defaultDiskType, loc))
      case V_String(s) =>
        val t = s.split("\\s").toVector match {
          case Vector(size) =>
            val bytes = EvalUtils.floatToInt(EvalUtils.sizeStringToFloat(size, loc, "GiB"))
            DiskRequest(bytes, defaultMountPoint, defaultDiskType)
          case Vector(a, b) =>
            try {
              // "<size> <suffix>"
              DiskRequest(EvalUtils.floatToInt(EvalUtils.sizeToFloat(a.toDouble, b, loc)),
                          defaultMountPoint,
                          defaultDiskType)
            } catch {
              case _: Throwable =>
                DiskRequest(EvalUtils.floatToInt(EvalUtils.sizeStringToFloat(b, loc, "GiB")),
                            Some(a),
                            defaultDiskType)
            }
          case Vector(a, b, c) =>
            try {
              // "<mount-point> <size> <suffix>"
              DiskRequest(EvalUtils.floatToInt(EvalUtils.sizeToFloat(b.toDouble, c, loc)),
                          defaultMountPoint,
                          defaultDiskType)
            } catch {
              case _: Throwable =>
                // "<mount-point> <size> <disk type>"
                DiskRequest(EvalUtils.floatToInt(EvalUtils.sizeStringToFloat(b, loc, "GiB")),
                            Some(a),
                            Some(c))
            }
          case Vector(a, b, c, d) =>
            val bytes = EvalUtils.floatToInt(EvalUtils.sizeToFloat(b.toDouble, c, loc))
            DiskRequest(bytes, Some(a), Some(d))
          case other =>
            throw new EvalException(
                s"Invalid ${Runtime.DisksKey} value ${other}",
                loc
            )
        }
        Vector(t)
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.DisksKey} value ${other}",
            loc
        )
    }
  }
}
