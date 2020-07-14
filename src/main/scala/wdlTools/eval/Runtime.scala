package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.{WdlTypes, TypedAbstractSyntax => TAT}
import wdlTools.util.SymmetricBiMap

abstract class Runtime(runtime: Map[String, TAT.Expr],
                       userDefaultValues: Map[String, V],
                       runtimeLocation: SourceLocation) {
  private var cache: Map[String, Option[V]] = Map.empty

  val defaults: Map[String, V]

  val aliases: SymmetricBiMap[String] = SymmetricBiMap.empty

  protected def applyKv(id: String, expr: TAT.Expr): V

  def getValue(id: String): Option[V] = {
    if (!cache.contains(id)) {
      val value = runtime.get(id) match {
        case Some(expr)                   => Some(applyKv(id, expr))
        case None if aliases.contains(id) => getValue(aliases.get(id))
        case None                         => userDefaultValues.get(id).orElse(defaults.get(id))
      }
      cache += (id -> value)
    }
    cache(id)
  }

  def getAll: Map[String, WdlValues.V] = {
    runtime.keys.map(key => key -> getValue(key).get).toMap
  }

  def getSourceLocation(id: String): SourceLocation = {
    runtime.get(id) match {
      case Some(v)                      => v.loc
      case None if aliases.contains(id) => getSourceLocation(aliases.get(id))
      case None                         => runtimeLocation
    }
  }

  def container: Vector[String]

  def memory: Option[Long]

  def disks: Vector[(Long, Option[String], Option[String])]

  def isValidReturnCode(returnCode: Int): Boolean = {
    val loc = getSourceLocation(Runtime.Keys.ReturnCodes)
    getValue(Runtime.Keys.ReturnCodes) match {
      case None                => returnCode == 0
      case Some(V_String("*")) => true
      case Some(V_Int(i))      => returnCode == i.toInt
      case Some(V_Array(v)) =>
        v.exists {
          case V_Int(i) => returnCode == i.toInt
          case other =>
            throw new EvalException(
                s"Invalid ${Runtime.Keys.ReturnCodes} array item value ${other}",
                loc
            )
        }
      case other =>
        throw new EvalException(s"Invalid ${Runtime.Keys.ReturnCodes} value ${other}", loc)
    }
  }
}

case class DefaultRuntime(runtime: Option[TAT.RuntimeSection],
                          ctx: Context,
                          evaluator: Eval,
                          userDefaultValues: Map[String, V] = Map.empty,
                          runtimeLocation: SourceLocation)
    extends Runtime(runtime.map(_.kvs).getOrElse(Map.empty), userDefaultValues, runtimeLocation) {
  val defaults: Map[String, V] = Map.empty

  override protected def applyKv(id: String, expr: TAT.Expr): V = {
    evaluator.applyExpr(expr, ctx)
  }

  lazy val container: Vector[String] = {
    getValue(Runtime.Keys.Docker) match {
      case None              => Vector.empty
      case Some(V_String(s)) => Vector(s)
      case Some(V_Array(a)) =>
        a.map {
          case V_String(s) => s
          case other =>
            throw new EvalException(s"Invalid ${Runtime.Keys.Docker} array item value ${other}",
                                    getSourceLocation(Runtime.Keys.Docker))
        }
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.Keys.Docker} value ${other}",
            getSourceLocation(Runtime.Keys.Docker)
        )
    }
  }

  lazy val memory: Option[Long] = {
    getValue(Runtime.Keys.Memory) match {
      case None           => None
      case Some(V_Int(i)) => Some(i)
      case Some(V_String(s)) =>
        val d = Util.sizeStringToFloat(s, getSourceLocation(Runtime.Keys.Memory))
        Some(Util.floatToInt(d))
      case other =>
        throw new EvalException(s"Invalid ${Runtime.Keys.Memory} value ${other}",
                                getSourceLocation(Runtime.Keys.Memory))
    }
  }

  lazy val disks: Vector[(Long, Option[String], Option[String])] = {
    getValue(Runtime.Keys.Disks) match {
      case None    => Vector.empty
      case Some(v) => Runtime.parseDisks(v, loc = getSourceLocation(Runtime.Keys.Disks))
    }
  }
}

case class V2Runtime(runtime: Option[TAT.RuntimeSection],
                     ctx: Context,
                     evaluator: Eval,
                     userDefaultValues: Map[String, V] = Map.empty,
                     runtimeLocation: SourceLocation)
    extends Runtime(runtime.map(_.kvs).getOrElse(Map.empty), userDefaultValues, runtimeLocation) {
  val defaults: Map[String, V] = Map(
      Runtime.Keys.Cpu -> V_Int(1),
      Runtime.Keys.Memory -> V_String("2 GiB"),
      Runtime.Keys.Gpu -> V_Boolean(false),
      Runtime.Keys.Disks -> V_String("1 GiB"),
      Runtime.Keys.MaxRetries -> V_Int(0),
      Runtime.Keys.ReturnCodes -> V_Int(0)
  )

  override val aliases: SymmetricBiMap[String] =
    SymmetricBiMap(Map(Runtime.Keys.Docker -> Runtime.Keys.Container))

  protected def applyKv(id: String, expr: TAT.Expr): V = {
    def getValue(allowed: Vector[WdlTypes.T]): V = {
      evaluator.applyExprAndCoerce(expr, allowed, ctx)
    }

    id match {
      // allow 'docker' even in WDL 2.0, for backward-compatibility
      case Runtime.Keys.Container =>
        getValue(Vector(WdlTypes.T_String, WdlTypes.T_Array(WdlTypes.T_String)))
      case Runtime.Keys.Cpu =>
        // always return cpu as a float
        getValue(Vector(WdlTypes.T_Float))
      case Runtime.Keys.Memory =>
        // always return memory in bytes (round up to nearest byte)
        getValue(Vector(WdlTypes.T_Int, WdlTypes.T_String)) match {
          case i: V_Int => i
          case V_String(s) =>
            val d = Util.sizeStringToFloat(s, expr.loc)
            V_Int(Util.floatToInt(d))
          case other =>
            throw new EvalException(s"Invalid ${Runtime.Keys.Memory} value ${other}",
                                    getSourceLocation(Runtime.Keys.Memory))
        }
      case Runtime.Keys.Disks =>
        getValue(Vector(WdlTypes.T_Int, WdlTypes.T_Array(WdlTypes.T_String), WdlTypes.T_String)) match {
          case i: V_Int => V_String(s"${i} GiB")
          case v        => v
        }
      case Runtime.Keys.Gpu =>
        getValue(Vector(WdlTypes.T_Boolean))
      case Runtime.Keys.MaxRetries =>
        getValue(Vector(WdlTypes.T_Int))
      case Runtime.Keys.ReturnCodes =>
        getValue(Vector(WdlTypes.T_Int, WdlTypes.T_Array(WdlTypes.T_Int), WdlTypes.T_String))
      case other =>
        throw new EvalException(s"Runtime key ${other} not allowed in WDL version 2.0+", expr.loc)
    }
  }

  lazy val container: Vector[String] = {
    getValue(Runtime.Keys.Container) match {
      case None              => Vector.empty
      case Some(V_String(s)) => Vector(s)
      case Some(V_Array(a)) =>
        a.map {
          case V_String(s) => s
          case other       => throw new EvalException(s"Invalid docker array item value ${other}")
        }
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.Keys.Container} value ${other}",
            getSourceLocation(Runtime.Keys.Container)
        )
    }
  }

  lazy val memory: Option[Long] = {
    getValue(Runtime.Keys.Memory).map {
      case V_Int(i) => i
      case other    => throw new EvalException(s"Invalid memory value ${other}")
    }
  }

  lazy val disks: Vector[(Long, Option[String], Option[String])] = {
    val loc = getSourceLocation(Runtime.Keys.Disks)
    getValue(Runtime.Keys.Disks) match {
      case None =>
        throw new EvalException(s"No value for 'disks'", loc)
      case Some(v) =>
        Runtime.parseDisks(v, loc = loc).map {
          case (_, _, Some(diskType)) =>
            throw new EvalException(
                s"In WDL 2.0, it is not allowed to define the disk type (${diskType}) in runtime.disks",
                loc
            )
          case other => other
        }
    }
  }
}

object Runtime {
  object Keys {
    val Container = "container"
    val Cpu = "cpu"
    val Disks = "disks"
    val Docker = "docker"
    val Gpu = "gpu"
    val MaxRetries = "maxRetries"
    val Memory = "memory"
    val ReturnCodes = "returnCodes"
  }

  def fromTask(
      task: TAT.Task,
      ctx: Context,
      evaluator: Eval,
      defaultValues: Map[String, V] = Map.empty
  ): Runtime = {
    create(task.runtime,
           ctx,
           evaluator,
           defaultValues,
           Some(task.runtime.map(_.loc).getOrElse(task.loc)))
  }

  def create(
      runtime: Option[TAT.RuntimeSection],
      ctx: Context,
      evaluator: Eval,
      defaultValues: Map[String, V] = Map.empty,
      runtimeLocation: Option[SourceLocation] = None
  ): Runtime = {
    val loc = runtime
      .map(_.loc)
      .orElse(runtimeLocation)
      .getOrElse(
          throw new RuntimeException("either 'runtime' or 'runtimeLocation' must be nonEmpty")
      )
    evaluator.wdlVersion match {
      case WdlVersion.V2 => V2Runtime(runtime, ctx, evaluator, defaultValues, loc)
      case _             => DefaultRuntime(runtime, ctx, evaluator, defaultValues, loc)
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
      loc: SourceLocation
  ): Vector[(Long, Option[String], Option[String])] = {
    value match {
      case V_Int(i) =>
        val bytes = Util.floatToInt(Util.sizeToFloat(i.toDouble, "GiB", loc))
        Vector((bytes, defaultMountPoint, defaultDiskType))
      case V_Array(a) =>
        a.flatMap(v => parseDisks(v, defaultMountPoint, defaultDiskType, loc))
      case V_String(s) =>
        val t = s.split("\\s").toVector match {
          case Vector(size) =>
            val bytes = Util.floatToInt(Util.sizeStringToFloat(size, loc, "GiB"))
            (bytes, defaultMountPoint, defaultDiskType)
          case Vector(a, b) =>
            try {
              // "<size> <suffix>"
              (Util.floatToInt(Util.sizeToFloat(a.toDouble, b, loc)),
               defaultMountPoint,
               defaultDiskType)
            } catch {
              case _: Throwable =>
                (Util.floatToInt(Util.sizeStringToFloat(b, loc, "GiB")), Some(a), defaultDiskType)
            }
          case Vector(a, b, c) =>
            try {
              // "<mount-point> <size> <suffix>"
              (Util.floatToInt(Util.sizeToFloat(b.toDouble, c, loc)),
               defaultMountPoint,
               defaultDiskType)
            } catch {
              case _: Throwable =>
                // "<mount-point> <size> <disk type>"
                (Util.floatToInt(Util.sizeStringToFloat(b, loc, "GiB")), Some(a), Some(c))
            }
          case Vector(a, b, c, d) =>
            val bytes = Util.floatToInt(Util.sizeToFloat(b.toDouble, c, loc))
            (bytes, Some(a), Some(d))
        }
        Vector(t)
      case other =>
        throw new EvalException(
            s"Invalid ${Runtime.Keys.Disks} value ${other}",
            loc
        )
    }
  }
}
