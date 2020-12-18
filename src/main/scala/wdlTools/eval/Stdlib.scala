package wdlTools.eval

import java.nio.file.{Path, Paths}
import com.google.re2j.Pattern
import dx.util.{EvalPaths, FileSourceResolver, Logger}
import kantan.csv.CsvConfiguration.{Header, QuotePolicy}
import kantan.csv._
import kantan.csv.ops._
import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{Builtins, Operator, SourceLocation, WdlVersion}
import wdlTools.types.{ExprState, UserDefinedFunctionPrototype, WdlTypes}
import wdlTools.types.WdlTypes._

import scala.collection.immutable.{SeqMap, TreeSeqMap}
import scala.io.Source
import scala.util.matching.Regex

case class FunctionContext(args: Vector[WdlValues.V],
                           exprState: ExprState.ExprState,
                           paths: EvalPaths,
                           loc: SourceLocation) {
  def assertNumArgs(required: Int, optional: Boolean = false): Unit = {
    val numArgs = args.size
    val maxArgs = required + (if (optional) 1 else 0)
    if (numArgs < required || numArgs > maxArgs) {
      val argRange = if (optional) s"${required}-${maxArgs}" else s"exactly ${required}"
      throw new EvalException(s"Invalid arguments ${args}, expected ${argRange}", loc)
    }
  }

  def assertNoArgs(): Unit = {
    assertNumArgs(0)
  }

  def getOneArg: V = {
    args match {
      case Vector(arg) => arg
      case _ =>
        throw new EvalException(s"Invalid arguments ${args}, expected exactly two", loc)
    }
  }

  def getTwoArgs: (V, V) = {
    args match {
      case Vector(arg1, arg2) => (arg1, arg2)
      case _ =>
        throw new EvalException(s"Invalid arguments ${args}, expected exactly two", loc)
    }
  }

  def getThreeArgs: (V, V, V) = {
    args match {
      case Vector(arg1, arg2, arg3) => (arg1, arg2, arg3)
      case _ =>
        throw new EvalException(s"Invalid arguments ${args}, expected exactly three", loc)
    }
  }
}

trait UserDefinedFunctionImplFactory {

  /**
    * Returns the implementation of this UDF for the given function name and arguments,
    * or None if this UDF cannot evaluate the arguments.
    * @param funcName function name
    * @param args function arguments
    * @return
    */
  def getImpl(funcName: String, args: Vector[V], loc: SourceLocation): Option[FunctionContext => V]
}

/**
  * Base class for generic user-defined functions. Provides caching of prototypes.
  * @param pattern regular expression to match to function names
  */
abstract class GenericUserDefinedFunction(pattern: Regex)
    extends UserDefinedFunctionPrototype
    with UserDefinedFunctionImplFactory {
  private var prototypeCache: Map[String, Option[WdlTypes.T_Function]] = Map.empty
  private var taskProxyCache: Map[String, Option[(WdlTypes.T_Function, Vector[String])]] = Map.empty

  def nameMatches(name: String): Boolean = pattern.matches(name)

  protected def createPrototype(funcName: String,
                                inputTypes: Vector[WdlTypes.T]): Option[WdlTypes.T_Function]

  override def getPrototype(funcName: String,
                            inputTypes: Vector[WdlTypes.T]): Option[WdlTypes.T_Function] = {
    if (nameMatches(funcName)) {
      if (prototypeCache.contains(funcName)) {
        prototypeCache(funcName)
      } else {
        val prototype = createPrototype(funcName, inputTypes)
        prototypeCache += (funcName -> prototype)
        prototype
      }
    } else {
      None
    }
  }

  protected def createTaskProxyFunction(
      taskName: String,
      input: SeqMap[String, (WdlTypes.T, Boolean)],
      output: SeqMap[String, WdlTypes.T]
  ): Option[(WdlTypes.T_Function, Vector[String])]

  override def getTaskProxyFunction(
      taskName: String,
      input: SeqMap[String, (WdlTypes.T, Boolean)],
      output: SeqMap[String, WdlTypes.T]
  ): Option[(WdlTypes.T_Function, Vector[String])] = {
    if (nameMatches(taskName)) {
      if (taskProxyCache.contains(taskName)) {
        taskProxyCache(taskName)
      } else {
        val taskProxy = createTaskProxyFunction(taskName, input, output)
        taskProxyCache += (taskName -> taskProxy)
        taskProxy
      }
    } else {
      None
    }
  }

  protected def createImpl(prototype: WdlTypes.T_Function,
                           args: Vector[WdlValues.V],
                           loc: SourceLocation): Option[FunctionContext => WdlValues.V]

  override def getImpl(funcName: String,
                       args: Vector[WdlValues.V],
                       loc: SourceLocation): Option[FunctionContext => WdlValues.V] = {
    if (nameMatches(funcName)) {
      val prototype = prototypeCache
        .get(funcName)
        .flatten
        .orElse(taskProxyCache.get(funcName).flatten.map(_._1))
        .getOrElse(
            throw new EvalException(s"no prototype for generic function ${funcName}")
        )
      createImpl(prototype, args, loc)
    } else {
      None
    }
  }
}

case class Stdlib(paths: EvalPaths,
                  version: WdlVersion,
                  userDefinedFunctions: Vector[UserDefinedFunctionImplFactory] = Vector.empty,
                  fileResolver: FileSourceResolver = FileSourceResolver.get,
                  logger: Logger = Logger.get) {

  private val ioSupport: IoSupport = IoSupport(paths, fileResolver, logger)

  private type FunctionImpl = FunctionContext => V

  // built-in operators
  private val builtinFuncTable: Map[String, FunctionImpl] = Map(
      Operator.UnaryMinus.name -> unaryMinus,
      Operator.UnaryPlus.name -> unaryPlus,
      Operator.LogicalNot.name -> logicalNot,
      Operator.LogicalAnd.name -> logicalAnd,
      Operator.LogicalOr.name -> logicalOr,
      Operator.Equality.name -> equality,
      Operator.Inequality.name -> inequality,
      Operator.LessThan.name -> lessThan,
      Operator.LessThanOrEqual.name -> lessThanOrEqual,
      Operator.GreaterThan.name -> greaterThan,
      Operator.GreaterThanOrEqual.name -> greaterThanOrEqual,
      Operator.Addition.name -> addition,
      Operator.Subtraction.name -> subtraction,
      Operator.Multiplication.name -> multiplication,
      Operator.Division.name -> division,
      Operator.Remainder.name -> remainder
  )

  private lazy val draft2FuncTable: Map[String, FunctionImpl] = Map(
      // standard library functions
      Builtins.Stdout -> stdout,
      Builtins.Stderr -> stderr,
      // read from a file
      Builtins.ReadLines -> read_lines,
      Builtins.ReadTsv -> read_tsv,
      Builtins.ReadMap -> read_map,
      Builtins.ReadObject -> read_object,
      Builtins.ReadObjects -> read_objects,
      Builtins.ReadJson -> read_json,
      Builtins.ReadInt -> read_int,
      Builtins.ReadString -> read_string,
      Builtins.ReadFloat -> read_float,
      Builtins.ReadBoolean -> read_boolean,
      // write to a file
      Builtins.WriteLines -> write_lines,
      Builtins.WriteTsv -> write_tsv,
      Builtins.WriteMap -> write_map,
      Builtins.WriteObject -> write_object,
      Builtins.WriteObjects -> write_objects,
      Builtins.WriteJson -> write_json,
      // other functions
      Builtins.Size -> size,
      Builtins.Sub -> sub,
      Builtins.Range -> range,
      Builtins.Transpose -> transpose,
      Builtins.Zip -> zip,
      Builtins.Cross -> cross,
      Builtins.Length -> length,
      Builtins.Flatten -> flatten,
      Builtins.Prefix -> prefix,
      Builtins.SelectFirst -> select_first,
      Builtins.SelectAll -> select_all,
      Builtins.Defined -> defined,
      Builtins.Basename -> basename,
      Builtins.Floor -> floor,
      Builtins.Ceil -> ceil,
      Builtins.Round -> round,
      Builtins.Glob -> glob
  )

  private lazy val v1FuncTable: Map[String, FunctionImpl] = Map(
      Builtins.Stdout -> stdout,
      Builtins.Stderr -> stderr,
      // read from a file
      Builtins.ReadLines -> read_lines,
      Builtins.ReadTsv -> read_tsv,
      Builtins.ReadMap -> read_map,
      Builtins.ReadObject -> read_object,
      Builtins.ReadObjects -> read_objects,
      Builtins.ReadJson -> read_json,
      Builtins.ReadInt -> read_int,
      Builtins.ReadString -> read_string,
      Builtins.ReadFloat -> read_float,
      Builtins.ReadBoolean -> read_boolean,
      // write to a file
      Builtins.WriteLines -> write_lines,
      Builtins.WriteTsv -> write_tsv,
      Builtins.WriteMap -> write_map,
      Builtins.WriteObject -> write_object,
      Builtins.WriteObjects -> write_objects,
      Builtins.WriteJson -> write_json,
      // other functions
      Builtins.Size -> size,
      Builtins.Sub -> sub,
      Builtins.Range -> range,
      Builtins.Transpose -> transpose,
      Builtins.Zip -> zip,
      Builtins.Cross -> cross,
      Builtins.Length -> length,
      Builtins.Flatten -> flatten,
      Builtins.Prefix -> prefix,
      Builtins.SelectFirst -> select_first,
      Builtins.SelectAll -> select_all,
      Builtins.Defined -> defined,
      Builtins.Basename -> basename,
      Builtins.Floor -> floor,
      Builtins.Ceil -> ceil,
      Builtins.Round -> round,
      Builtins.Glob -> glob
  )

  private lazy val v2FuncTable: Map[String, FunctionImpl] = Map(
      // numeric
      Builtins.Floor -> floor,
      Builtins.Ceil -> ceil,
      Builtins.Round -> round,
      Builtins.Min -> min,
      Builtins.Max -> max,
      // string
      Builtins.Sub -> sub,
      // file
      Builtins.Stdout -> stdout,
      Builtins.Stderr -> stderr,
      Builtins.Glob -> glob,
      Builtins.Basename -> basename,
      // read from a file
      Builtins.ReadLines -> read_lines,
      Builtins.ReadTsv -> read_tsv,
      Builtins.ReadMap -> read_map,
      Builtins.ReadJson -> read_json,
      Builtins.ReadString -> read_string,
      Builtins.ReadInt -> read_int,
      Builtins.ReadFloat -> read_float,
      Builtins.ReadBoolean -> read_boolean,
      // write to a file
      Builtins.WriteLines -> write_lines,
      Builtins.WriteTsv -> write_tsv,
      Builtins.WriteMap -> write_map,
      Builtins.WriteJson -> write_json,
      Builtins.Size -> size,
      // array
      Builtins.Length -> length,
      Builtins.Range -> range,
      Builtins.Transpose -> transpose,
      Builtins.Zip -> zip,
      Builtins.Cross -> cross,
      Builtins.Flatten -> flatten,
      Builtins.Prefix -> prefix,
      Builtins.Suffix -> suffix,
      Builtins.Quote -> quote,
      Builtins.Squote -> squote,
      Builtins.Sep -> sep,
      // map
      Builtins.AsPairs -> as_pairs,
      Builtins.AsMap -> as_map,
      Builtins.Keys -> keys,
      Builtins.CollectByKey -> collect_by_key,
      // optional
      Builtins.SelectFirst -> select_first,
      Builtins.SelectAll -> select_all,
      Builtins.Defined -> defined
  )

  // choose the standard library prototypes according to the WDL version
  private val funcTable: Map[String, FunctionImpl] = version match {
    case WdlVersion.Draft_2 => builtinFuncTable ++ draft2FuncTable
    case WdlVersion.V1      => builtinFuncTable ++ v1FuncTable
    case WdlVersion.V2      => builtinFuncTable ++ v2FuncTable
    case other              => throw new RuntimeException(s"Unsupported WDL version ${other}")
  }

  def call(funcName: String,
           args: Vector[V],
           loc: SourceLocation,
           exprState: ExprState.ExprState = ExprState.Start): V = {
    val impl = funcTable.getOrElse(
        funcName, {
          userDefinedFunctions.iterator
            .map(udf => udf.getImpl(funcName, args, loc))
            .collectFirst {
              case impl if impl.isDefined => impl.get
            }
            .getOrElse(throw new EvalException(s"stdlib function ${funcName} not implemented", loc))
        }
    )
    val ctx = FunctionContext(args, exprState, paths, loc)
    try {
      impl(ctx)
    } catch {
      case e: EvalException =>
        throw e
      case e: Throwable =>
        throw new EvalException(s"calling stdlib function ${funcName} with arguments ${args}",
                                loc,
                                e)
    }
  }

  private def getWdlFile(arg: V, loc: SourceLocation): V_File = {
    Coercion.coerceTo(T_File, arg, loc) match {
      case f: V_File => f
      case _         => throw new RuntimeException("Invalid coercion")
    }
  }

  private def getWdlInt(arg: V, loc: SourceLocation): Long = {
    Coercion.coerceTo(T_Int, arg, loc) match {
      case V_Int(value) => value
      case _            => throw new RuntimeException("Invalid coercion")
    }
  }

  private def getWdlFloat(arg: V, loc: SourceLocation): Double = {
    Coercion.coerceTo(T_Float, arg, loc) match {
      case V_Float(f) => f
      case _          => throw new RuntimeException("Invalid coercion")
    }
  }

  private def getWdlString(arg: V, loc: SourceLocation): String = {
    Coercion.coerceTo(T_String, arg, loc) match {
      case V_String(s) => s
      case _           => throw new RuntimeException("Invalid coercion")
    }
  }

  private def getWdlBoolean(arg: V, loc: SourceLocation): Boolean = {
    Coercion.coerceTo(T_Boolean, arg, loc) match {
      case V_Boolean(b) => b
      case _            => throw new RuntimeException("Invalid coercion")
    }
  }

  private def getWdlVector(value: V, loc: SourceLocation): Vector[V] = {
    value match {
      case V_Array(ar) => ar
      case other       => throw new EvalException(s"${other} should be an array", loc)
    }
  }

  private def getWdlMap(value: V, loc: SourceLocation): SeqMap[V, V] = {
    value match {
      case V_Map(m) => m
      case other    => throw new EvalException(s"${other} should be a map", loc)
    }
  }

  private def getWdlPair(value: V, loc: SourceLocation): (V, V) = {
    value match {
      case V_Pair(l, r) => (l, r)
      case other        => throw new EvalException(s"${other} should be a pair", loc)
    }
  }

  // built-in operators
  private def unaryMinus(ctx: FunctionContext): V_Numeric = {
    ctx.getOneArg match {
      case V_Int(i)   => V_Int(-i)
      case V_Float(f) => V_Float(-f)
      case other =>
        throw new EvalException(
            s"Invalid operand of unary minus ${other}",
            ctx.loc
        )
    }
  }

  private def unaryPlus(ctx: FunctionContext): V_Numeric = {
    ctx.getOneArg match {
      case i: V_Int   => i
      case f: V_Float => f
      case other =>
        throw new EvalException(
            s"Invalid operand of unary plus ${other}",
            ctx.loc
        )
    }
  }

  private def logicalNot(ctx: FunctionContext): V_Boolean = {
    V_Boolean(!getWdlBoolean(ctx.getOneArg, ctx.loc))
  }

  private def logicalBinary(ctx: FunctionContext, op: (Boolean, Boolean) => Boolean): V_Boolean = {
    ctx.getTwoArgs match {
      case (V_Boolean(a), V_Boolean(b)) =>
        V_Boolean(op(a, b))
      case _ =>
        throw new EvalException(
            s"Invalid operands of logical operator ${ctx.args}",
            ctx.loc
        )
    }
  }

  private def logicalAnd(ctx: FunctionContext): V_Boolean =
    logicalBinary(ctx, _ && _)

  private def logicalOr(ctx: FunctionContext): V_Boolean =
    logicalBinary(ctx, _ || _)

  private def equality(ctx: FunctionContext): V_Boolean = {
    def inner(l: V, r: V): Boolean = {
      (l, r) match {
        case (V_Null, V_Null)                                 => true
        case (V_Optional(v1), V_Optional(v2))                 => inner(v1, v2)
        case (V_Optional(v1), v2)                             => inner(v1, v2)
        case (v1, V_Optional(v2))                             => inner(v1, v2)
        case (V_Int(n1), V_Int(n2))                           => n1 == n2
        case (V_Float(x1), V_Int(n2))                         => x1 == n2
        case (V_Int(n1), V_Float(x2))                         => n1 == x2
        case (V_Float(x1), V_Float(x2))                       => x1 == x2
        case (V_String(s1), V_String(s2))                     => s1 == s2
        case (V_Boolean(b1), V_Boolean(b2))                   => b1 == b2
        case (V_File(f1), V_File(f2))                         => f1 == f2
        case (V_File(p1), V_String(p2))                       => p1 == p2
        case (V_Directory(d1), V_Directory(d2))               => d1 == d2
        case (V_Array(a1), V_Array(a2)) if a1.size != a2.size => false
        case (V_Array(a1), V_Array(a2)) =>
          a1.zip(a2).forall {
            case (x, y) => inner(x, y)
          }
        case (V_Pair(l1, r1), V_Pair(l2, r2)) =>
          inner(l1, l2) && inner(r1, r2)
        case (V_Map(m1), V_Map(m2)) if m1.size != m2.size => false
        case (V_Map(m1), V_Map(m2)) =>
          m1.zip(m2).forall {
            case ((k1, v1), (k2, v2)) => inner(k1, k2) && inner(v1, v2)
          }
        case (V_Struct(name1, members1), V_Struct(name2, members2))
            if name1 != name2 || members1.keySet != members2.keySet =>
          false
        case (V_Struct(_, members1), V_Struct(_, members2)) =>
          members1.keys.forall(k => inner(members1(k), members2(k)))
        case (V_Object(members1), V_Object(members2)) if members1.keySet != members2.keySet => false
        case (V_Object(members1), V_Object(members2)) =>
          members1.keys.forall(k => inner(members1(k), members2(k)))
        case other =>
          throw new EvalException(s"Invalid operands to == ${other}", ctx.loc)
      }
    }
    ctx.getTwoArgs match {
      case (l, r) => V_Boolean(inner(l, r))
      case _      => throw new RuntimeException(s"Invalid number of operands to ==")
    }
  }

  private def inequality(ctx: FunctionContext): V_Boolean = {
    V_Boolean(!equality(ctx).value)
  }

  private def lessThan(ctx: FunctionContext): V_Boolean = {
    val result = ctx.getTwoArgs match {
      case (V_Null, V_Null)               => false
      case (V_Int(n1), V_Int(n2))         => n1 < n2
      case (V_Float(x1), V_Int(n2))       => x1 < n2
      case (V_Int(n1), V_Float(x2))       => n1 < x2
      case (V_Float(x1), V_Float(x2))     => x1 < x2
      case (V_String(s1), V_String(s2))   => s1 < s2
      case (V_Boolean(b1), V_Boolean(b2)) => b1 < b2
      case other =>
        throw new EvalException(s"Invalid operands to < ${other}", ctx.loc)
    }
    V_Boolean(result)
  }

  private def lessThanOrEqual(ctx: FunctionContext): V_Boolean = {
    val result = ctx.getTwoArgs match {
      case (V_Null, V_Null)               => false
      case (V_Int(n1), V_Int(n2))         => n1 <= n2
      case (V_Float(x1), V_Int(n2))       => x1 <= n2
      case (V_Int(n1), V_Float(x2))       => n1 <= x2
      case (V_Float(x1), V_Float(x2))     => x1 <= x2
      case (V_String(s1), V_String(s2))   => s1 <= s2
      case (V_Boolean(b1), V_Boolean(b2)) => b1 <= b2
      case other =>
        throw new EvalException(s"Invalid operands to <= ${other}", ctx.loc)
    }
    V_Boolean(result)
  }

  private def greaterThan(ctx: FunctionContext): V_Boolean = {
    val result = ctx.getTwoArgs match {
      case (V_Null, V_Null)               => false
      case (V_Int(n1), V_Int(n2))         => n1 > n2
      case (V_Float(x1), V_Int(n2))       => x1 > n2
      case (V_Int(n1), V_Float(x2))       => n1 > x2
      case (V_Float(x1), V_Float(x2))     => x1 > x2
      case (V_String(s1), V_String(s2))   => s1 > s2
      case (V_Boolean(b1), V_Boolean(b2)) => b1 > b2
      case other =>
        throw new EvalException(s"Invalid operands to > ${other}", ctx.loc)
    }
    V_Boolean(result)
  }

  private def greaterThanOrEqual(ctx: FunctionContext): V_Boolean = {
    val result = ctx.getTwoArgs match {
      case (V_Null, V_Null)               => false
      case (V_Int(n1), V_Int(n2))         => n1 >= n2
      case (V_Float(x1), V_Int(n2))       => x1 >= n2
      case (V_Int(n1), V_Float(x2))       => n1 >= x2
      case (V_Float(x1), V_Float(x2))     => x1 >= x2
      case (V_String(s1), V_String(s2))   => s1 >= s2
      case (V_Boolean(b1), V_Boolean(b2)) => b1 >= b2
      case other =>
        throw new EvalException(s"Invalid operands to >= ${other}", ctx.loc)
    }
    V_Boolean(result)
  }

  private def addition(ctx: FunctionContext): V = {
    def add2(a: V, b: V, exprState: ExprState.ExprState, loc: SourceLocation): V = {
      (a, b) match {
        case (V_Int(n1), V_Int(n2))     => V_Int(n1 + n2)
        case (V_Float(x1), V_Int(n2))   => V_Float(x1 + n2)
        case (V_Int(n1), V_Float(x2))   => V_Float(n1 + x2)
        case (V_Float(x1), V_Float(x2)) => V_Float(x1 + x2)
        // files
        case (V_File(s1), V_String(s2)) => V_File(s1 + s2)
        case (V_File(s1), V_File(s2))   => V_File(s1 + s2)
        // adding a string string to anything results in a string
        case (V_String(s1), V_String(s2))  => V_String(s1 + s2)
        case (V_String(s1), V_Int(n2))     => V_String(s1 + n2.toString)
        case (V_Int(n1), V_String(s2))     => V_String(n1.toString + s2)
        case (V_String(s1), V_Float(x2))   => V_String(s1 + x2.toString)
        case (V_Float(x1), V_String(s2))   => V_String(x1.toString + s2)
        case (V_String(s1), V_Boolean(b2)) => V_String(s1 + b2.toString)
        case (V_Boolean(b1), V_String(s2)) => V_String(b1.toString + s2)
        case (V_String(s1), V_File(s2))    => V_String(s1 + s2)
        // Addition of arguments with optional types is allowed within interpolations
        case (V_Null, _) if exprState >= ExprState.InPlaceholder =>
          V_Null
        case (_, V_Null) if exprState >= ExprState.InPlaceholder =>
          V_Null
        case (V_Optional(v1), V_Optional(v2)) if exprState >= ExprState.InPlaceholder =>
          V_Optional(add2(v1, v2, exprState, loc))
        case (V_Optional(v1), v2) if exprState >= ExprState.InPlaceholder =>
          V_Optional(add2(v1, v2, exprState, loc))
        case (v1, V_Optional(v2)) if exprState >= ExprState.InPlaceholder =>
          V_Optional(add2(v1, v2, exprState, loc))
        case other =>
          throw new EvalException(s"cannot add values ${other}", loc)
      }
    }
    ctx.getOneArg match {
      case V_Array(args) if args.size >= 2 =>
        args.reduce((a, b) => add2(a, b, ctx.exprState, ctx.loc))
      case _ =>
        throw new EvalException(s"illegal addition args ${ctx.args}", ctx.loc)
    }
  }

  private def subtraction(ctx: FunctionContext): V = {
    def subtract2(a: V, b: V, loc: SourceLocation): V = {
      (a, b) match {
        case (V_Int(n1), V_Int(n2))     => V_Int(n1 - n2)
        case (V_Float(x1), V_Int(n2))   => V_Float(x1 - n2)
        case (V_Int(n1), V_Float(x2))   => V_Float(n1 - x2)
        case (V_Float(x1), V_Float(x2)) => V_Float(x1 - x2)
        case other =>
          throw new EvalException(
              s"cannot subtract values ${other}; arguments must be integers or floats",
              loc
          )
      }
    }
    ctx.getOneArg match {
      case V_Array(args) if args.size >= 2 => args.reduce((a, b) => subtract2(a, b, ctx.loc))
      case _ =>
        throw new EvalException(s"illegal subtraction args ${ctx.args}", ctx.loc)
    }
  }

  private def multiplication(ctx: FunctionContext): V = {
    def multiply2(a: V, b: V, loc: SourceLocation): V = {
      (a, b) match {
        case (V_Int(n1), V_Int(n2))     => V_Int(n1 * n2)
        case (V_Float(x1), V_Int(n2))   => V_Float(x1 * n2)
        case (V_Int(n1), V_Float(x2))   => V_Float(n1 * x2)
        case (V_Float(x1), V_Float(x2)) => V_Float(x1 * x2)
        case other =>
          throw new EvalException(
              s"cannot multiply values ${other}; arguments must be integers or floats",
              loc
          )
      }
    }
    ctx.getOneArg match {
      case V_Array(args) if args.size >= 2 => args.reduce((a, b) => multiply2(a, b, ctx.loc))
      case _ =>
        throw new EvalException(s"illegal multiplication args ${ctx.args}", ctx.loc)
    }
  }

  private def division(ctx: FunctionContext): V = {
    def divide2(a: V, b: V, loc: SourceLocation): V = {
      (a, b) match {
        case (_, denominator: V_Numeric) if denominator.floatValue == 0 =>
          throw new EvalException("DivisionByZero", loc)
        case (V_Int(n1), V_Int(n2))     => V_Int(n1 / n2)
        case (V_Float(x1), V_Int(n2))   => V_Float(x1 / n2)
        case (V_Int(n1), V_Float(x2))   => V_Float(n1 / x2)
        case (V_Float(x1), V_Float(x2)) => V_Float(x1 / x2)
        case other =>
          throw new EvalException(
              s"cannot divide values ${other}; arguments must be integers or floats",
              loc
          )
      }
    }
    ctx.getOneArg match {
      case V_Array(args) if args.size >= 2 =>
        args.reduce((a, b) => divide2(a, b, ctx.loc))
      case _ =>
        throw new EvalException(s"illegal division args ${ctx.args}", ctx.loc)
    }
  }

  private def remainder(ctx: FunctionContext): V = {
    ctx.getTwoArgs match {
      case (_, denominator: V_Numeric) if denominator.floatValue == 0 =>
        throw new EvalException("DivisionByZero", ctx.loc)
      case (V_Int(n1), V_Int(n2))     => V_Int(n1 % n2)
      case (V_Float(x1), V_Int(n2))   => V_Float(x1 % n2)
      case (V_Int(n1), V_Float(x2))   => V_Float(n1 % x2)
      case (V_Float(x1), V_Float(x2)) => V_Float(x1 % x2)
      case other =>
        throw new EvalException(
            s"cannot take modulus of values ${other}; arguments must be integers or floats",
            ctx.loc
        )
    }
  }

  // since: draft-2
  private def floor(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.floor(x).toInt)
  }

  // since: draft-2
  private def ceil(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.ceil(x).toInt)
  }

  // since: draft-2
  private def round(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.round(x).toInt)
  }

  // since: v1.1
  private def min(ctx: FunctionContext): V_Numeric = {
    ctx.getTwoArgs match {
      case (V_Int(x), V_Int(y))     => V_Int(Math.min(x, y))
      case (V_Int(x), V_Float(y))   => V_Float(Math.min(x.toDouble, y))
      case (V_Float(x), V_Int(y))   => V_Float(Math.min(x, y.toDouble))
      case (V_Float(x), V_Float(y)) => V_Float(Math.min(x, y))
      case other =>
        throw new RuntimeException(s"Cannot apply min() to ${other}")
    }
  }

  // since: v1.1
  private def max(ctx: FunctionContext): V_Numeric = {
    ctx.getTwoArgs match {
      case (V_Int(x), V_Int(y))     => V_Int(Math.max(x, y))
      case (V_Int(x), V_Float(y))   => V_Float(Math.max(x.toDouble, y))
      case (V_Float(x), V_Int(y))   => V_Float(Math.max(x, y.toDouble))
      case (V_Float(x), V_Float(y)) => V_Float(Math.max(x, y))
      case other =>
        throw new RuntimeException(s"Cannot apply max() to ${other}")
    }
  }

  /**
    * Given 3 String parameters `input`, `pattern`, `replace`), this
    * function will replace any occurrence matching `pattern` in `input`
    * by `replace`. Pattern is expected to be a POSIX extended regular
    * expression. Pattern matching is done using the Google RE2 library.
    *
    * signature: String sub(String, String, String)
    * since: draft-2
    */
  private def sub(ctx: FunctionContext): V_String = {
    val (input, pattern, replace) = ctx.getThreeArgs
    val re2Pattern = Pattern.compile(getWdlString(pattern, ctx.loc), 0)
    val re2Matcher = re2Pattern.matcher(getWdlString(input, ctx.loc))
    val result = re2Matcher.replaceAll(getWdlString(replace, ctx.loc))
    V_String(result)
  }

  // since: draft-1
  private def stdout(ctx: FunctionContext): V_File = {
    ctx.assertNoArgs()
    val stdoutFile = paths.getStdoutFile(true)
    ioSupport.ensureFileExists(stdoutFile, "stdout", ctx.loc)
    V_File(stdoutFile.toString)
  }

  // since: draft-1
  private def stderr(ctx: FunctionContext): V_File = {
    ctx.assertNoArgs()
    val stderrFile = paths.getStdoutFile(true)
    ioSupport.ensureFileExists(stderrFile, "stderr", ctx.loc)
    V_File(stderrFile.toString)
  }

  // since: draft-2
  private def glob(ctx: FunctionContext): V_Array = {
    val pattern = getWdlString(ctx.getOneArg, ctx.loc)
    val filenames = ioSupport.glob(pattern)
    V_Array(filenames.map(filepath => V_File(filepath)))
  }

  // String basename(String)
  //
  // This function returns the basename of a file path passed to it: basename("/path/to/file.txt") returns "file.txt".
  // Also supports an optional parameter, suffix to remove: basename("/path/to/file.txt", ".txt") returns "file".
  // since: draft-2
  private def basename(ctx: FunctionContext): V_String = {
    ctx.assertNumArgs(required = 1, optional = true)
    val filePath = ctx.args.head match {
      case V_File(s)   => s
      case V_String(s) => s
      case other =>
        throw new EvalException(s"${other} must be a string or a file type", ctx.loc)
    }
    val filename = Paths.get(filePath).getFileName.toString
    if (ctx.args.size == 2) {
      V_String(filename.stripSuffix(getWdlString(ctx.args(1), ctx.loc)))
    } else {
      V_String(filename)
    }
  }

  // Array[String] read_lines(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_lines(ctx: FunctionContext): V_Array = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    V_Array(Source.fromString(content).getLines.map(x => V_String(x)).toVector)
  }

  private val tsvConf = CsvConfiguration('\t', '"', QuotePolicy.WhenNeeded, Header.None)

  // Array[Array[String]] read_tsv(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_tsv(ctx: FunctionContext): V_Array = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    val reader = content.asCsvReader[Vector[String]](tsvConf)
    V_Array(reader.map {
      case Left(err) =>
        throw new EvalException(s"Invalid tsv file ${file}: ${err}", ctx.loc)
      case Right(row) => V_Array(row.map(x => V_String(x)))
    }.toVector)
  }

  // Map[String, String] read_map(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_map(ctx: FunctionContext): V_Map = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    val reader = content.asCsvReader[(String, String)](tsvConf)
    V_Map(
        reader
          .map {
            case Left(err) =>
              throw new EvalException(s"Invalid tsv file ${file}: ${err}", ctx.loc)
            case Right((key, value)) => V_String(key) -> V_String(value)
          }
          .toVector
          .to(TreeSeqMap)
    )
  }

  private def kvToObject(funcName: String,
                         keys: Vector[String],
                         values: Vector[String],
                         loc: SourceLocation): V_Object = {
    if (keys.size != values.size) {
      throw new EvalException(
          s"""${funcName}: the number of keys (${keys.size})
             |must be the same as the number of values (${values.size})""".stripMargin
            .replace("\t", " "),
          loc
      )
    }
    val duplicated = keys.groupBy(identity).collect {
      case (key, values) if values.length > 1 => key
    }
    if (duplicated.nonEmpty) {
      throw new EvalException(
          s"${funcName}: duplicate keys found ${duplicated.mkString(",")}",
          loc
      )
    }
    V_Object(keys.zip(values.map(V_String)).toMap)
  }

  // Object read_object(String|File)
  //
  // since: draft-1
  // deprecation:
  // * beginning in draft-2, URI parameter is not supported
  // * deprecated in version 1.1
  // * removed in version 2
  private def read_object(ctx: FunctionContext): V_Object = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    val lines: Vector[Vector[String]] = content
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    lines match {
      case Vector(keys, values) => kvToObject("read_object", keys, values, ctx.loc)
      case _ =>
        throw new EvalException(
            s"read_object: file ${file.toString} must contain exactly two lines",
            ctx.loc
        )
    }
  }

  // Array[Object] read_objects(String|File)
  //
  // since: draft-1
  // deprecation:
  // * beginning in draft-2, URI parameter is not supported
  // * removed in Version 2
  private def read_objects(ctx: FunctionContext): V_Array = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    val lines = content
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    if (lines.size < 2) {
      throw new EvalException(
          s"read_object: file ${file.toString} must contain at least two lines",
          ctx.loc
      )
    }
    val keys = lines.head
    V_Array(lines.tail.map(values => kvToObject("read_objects", keys, values, ctx.loc)))
  }

  // mixed read_json(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_json(ctx: FunctionContext): V = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    try {
      WdlValueSerde.deserialize(content.parseJson)
    } catch {
      case e: WdlValueSerializationException =>
        throw new EvalException(e.getMessage, ctx.loc)
    }
  }

  // String read_string(String|File)
  //
  // The read_string() function takes a file path which is expected to
  // contain 1 line with 1 string on it. This function returns that
  // string.
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_string(ctx: FunctionContext): V_String = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    Source.fromString(content).getLines.toVector match {
      case Vector()     => V_String("")
      case Vector(line) => V_String(line)
      case _ =>
        throw new EvalException(s"file ${file} contains more than one line")
    }
  }

  // Int read_int(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_int(ctx: FunctionContext): V_Int = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    try {
      V_Int(content.trim.toInt)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert '${content}' to an integer", ctx.loc)
    }
  }

  // Float read_float(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_float(ctx: FunctionContext): V_Float = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    try {
      V_Float(content.trim.toDouble)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert '${content}' to a float", ctx.loc)
    }
  }

  // Boolean read_boolean(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  private def read_boolean(ctx: FunctionContext): V_Boolean = {
    val file = getWdlFile(ctx.getOneArg, ctx.loc)
    val content = ioSupport.readFile(file.value, ctx.loc)
    content.trim.toLowerCase() match {
      case "false" => V_Boolean(false)
      case "true"  => V_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert '${content}' to a boolean", ctx.loc)
    }
  }

  // File write_lines(Array[String])
  //
  // since: draft-1
  private def write_lines(ctx: FunctionContext): V_File = {
    val array: V_Array = Coercion.coerceTo(T_Array(T_String), ctx.getOneArg, ctx.loc) match {
      case a: V_Array => a
      case _          => throw new RuntimeException("failed coercion to Array[String]")
    }
    // ensure every line ends with a newline ("\n")
    val strRepr: String = array.value
      .map {
        case V_String(x) if !x.endsWith("\n") => s"${x}\n"
        case V_String(x)                      => x
        case other =>
          throw new EvalException(s"write_lines: element ${other} should be a string", ctx.loc)
      }
      .mkString("")
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    ioSupport.writeFile(tmpFile, strRepr, ctx.loc)
    V_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  // since: draft-1
  private def write_tsv(ctx: FunctionContext): V_File = {
    val matrix: V_Array =
      Coercion.coerceTo(T_Array(T_Array(T_String)), ctx.getOneArg, ctx.loc) match {
        case a: V_Array => a
        case _          => throw new RuntimeException("failed coercion to Array[String]")
      }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      matrix.value
        .foreach {
          case V_Array(a) =>
            val row = a.map {
              case V_String(s) => s
              case other =>
                throw new EvalException(s"${other} should be a string", ctx.loc)
            }
            writer.write(row)
          case other =>
            throw new EvalException(s"${other} should be an array", ctx.loc)
        }
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_map(Map[String, String])
  //
  // since: draft-1
  private def write_map(ctx: FunctionContext): V_File = {
    val m: V_Map =
      Coercion.coerceTo(T_Map(T_String, T_String), ctx.getOneArg, ctx.loc) match {
        case m: V_Map => m
        case _        => throw new RuntimeException("failed coercion to Map[String, String]")
      }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[(String, String)](tsvConf)
    try {
      m.value
        .foreach {
          case (V_String(key), V_String(value)) => writer.write((key, value))
          case (k, v) =>
            throw new EvalException(s"${k} ${v} should both be strings", ctx.loc)
        }
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  private def lineFromObject(obj: V_Object,
                             keys: Vector[String],
                             loc: SourceLocation): Vector[String] = {
    keys.map { key =>
      Coercion.coerceTo(T_String, obj.members(key), loc) match {
        case V_String(value) => value
        case _               => throw new RuntimeException("failed coercion to String")
      }
    }
  }

  // File write_object(Object)
  //
  // since: draft-1
  // deprecation:
  // - deprecated in version 1.1
  // - removed in version 2
  private def write_object(ctx: FunctionContext): V_File = {
    val obj = Coercion.coerceTo(T_Object, ctx.getOneArg, ctx.loc) match {
      case obj: V_Object => obj
      case _             => throw new RuntimeException("failed coercion to object")
    }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      val keys = obj.members.keys.toVector
      writer.write(keys)
      writer.write(lineFromObject(obj, keys, ctx.loc))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_objects(Array[Object])
  //
  // since: draft-1
  // deprecation: removed in Version 2
  private def write_objects(ctx: FunctionContext): V_File = {
    val objArray = Coercion.coerceTo(T_Array(T_Object), ctx.getOneArg, ctx.loc) match {
      case V_Array(array) =>
        array.map {
          case obj: V_Object => obj
          case _ =>
            throw new RuntimeException("failed coercion to Array[object]")
        }
      case _ => throw new RuntimeException("failed coercion to Array[object]")
    }
    if (objArray.isEmpty) {
      throw new EvalException("write_objects: empty input array", ctx.loc)
    }
    // check that all objects have the same keys
    val keys = objArray.head.members.keySet
    objArray.tail.foreach { obj =>
      if (obj.members.keySet != keys)
        throw new EvalException(
            "write_objects: member names must be the same for all objects in the array",
            ctx.loc
        )
    }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    val orderedKeys = keys.toVector
    try {
      writer.write(orderedKeys)
      objArray.foreach(obj => writer.write(lineFromObject(obj, orderedKeys, ctx.loc)))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_json(mixed)
  //
  // since: draft-1
  private def write_json(ctx: FunctionContext): V_File = {
    val jsv =
      try {
        WdlValueSerde.serialize(ctx.getOneArg)
      } catch {
        case e: WdlValueSerializationException =>
          throw new EvalException(e.getMessage, ctx.loc)
      }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".json")
    ioSupport.writeFile(tmpFile, jsv.prettyPrint, ctx.loc)
    V_File(tmpFile.toString)
  }

  // Size can take several kinds of arguments.
  //
  // since: draft-2
  // version differences: in 1.0, the spec was updated to explicitly support compount argument types;
  //  however, our implementation supports compound types for all versions, and goes further than the
  //  spec requires to support nested collections.
  private def size(ctx: FunctionContext): V_Float = {
    // Our size implementation is a little bit more general than strictly necessary.
    // recursively dive into the entire structure and sum up all the file sizes. Strings
    // are coerced into file paths.
    def sizeInBytes(arg: V, loc: SourceLocation): BigDecimal = {
      def inner(arg: V, loc: SourceLocation): BigDecimal = {
        arg match {
          case V_String(path)    => BigDecimal.valueOf(ioSupport.size(path, loc))
          case V_File(path)      => BigDecimal.valueOf(ioSupport.size(path, loc).toDouble)
          case V_Array(items)    => items.map(item => inner(item, loc)).sum
          case V_Optional(value) => inner(value, loc)
          case V_Null            => 0
          case _ =>
            throw new EvalException(s"size: invalid argument ${arg}")
        }
      }
      try {
        inner(arg, loc)
      } catch {
        case e: EvalException =>
          throw e
        case e: Throwable =>
          throw new EvalException(s"size: error getting size of ${arg}", loc, e)
      }
    }
    ctx.assertNumArgs(required = 1, optional = true)
    val nBytes = sizeInBytes(ctx.args.head, ctx.loc)
    val result = if (ctx.args.size == 2) {
      val unit = getWdlString(ctx.args(1), ctx.loc)
      val nBytesInUnit = EvalUtils.getSizeMultiplier(unit, ctx.loc)
      nBytes / BigDecimal.valueOf(nBytesInUnit)
    } else {
      nBytes
    }
    if (result.isExactDouble) {
      V_Float(result.toDouble)
    } else {
      throw new EvalException(s"size: overflow error - cannot represent size ${result} as a float",
                              ctx.loc)
    }
  }

  // Integer length(Array[X])
  //
  // since: draft-2
  private def length(ctx: FunctionContext): V_Int = {
    V_Int(getWdlVector(ctx.getOneArg, ctx.loc).size)
  }

  // Array[Int] range(Int)
  //
  // since: draft-2
  private def range(ctx: FunctionContext): V_Array = {
    val n = getWdlInt(ctx.getOneArg, ctx.loc).toInt
    V_Array(0.until(n).map(V_Int(_)).toVector)
  }

  // Array[Array[X]] transpose(Array[Array[X]])
  //
  // since: draft-2
  private def transpose(ctx: FunctionContext): V_Array = {
    val array: Vector[V] = getWdlVector(ctx.getOneArg, ctx.loc)
    val matrix: Vector[Vector[V]] = array.map(v => getWdlVector(v, ctx.loc))
    V_Array(matrix.transpose.map(vec => V_Array(vec)))
  }

  // Array[Pair(X,Y)] zip(Array[X], Array[Y])
  //
  // since: draft-2
  private def zip(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val xArray = getWdlVector(x, ctx.loc)
    val yArray = getWdlVector(y, ctx.loc)
    if (xArray.size != yArray.size) {
      throw new EvalException(
          s"zip: arrays are not of the same size (${xArray.size} != ${yArray.size})",
          ctx.loc
      )
    }
    V_Array(xArray.zip(yArray).map {
      case (x, y) => V_Pair(x, y)
    })
  }

  // Array[Pair(X,Y)] cross(Array[X], Array[Y])
  //
  // cartesian product of two arrays. Results in an n x m sized array of pairs.
  //
  // since: draft-2
  private def cross(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val xArray = getWdlVector(x, ctx.loc)
    val yArray = getWdlVector(y, ctx.loc)
    val product = xArray.flatMap(xi => yArray.map(yi => V_Pair(xi, yi)))
    V_Array(product)
  }

  // Array[X] flatten(Array[Array[X]])
  //
  // since: draft-2
  private def flatten(ctx: FunctionContext): V_Array = {
    val array: Vector[V] = getWdlVector(ctx.getOneArg, ctx.loc)
    val matrix: Vector[Vector[V]] = array.map(v => getWdlVector(v, ctx.loc))
    V_Array(matrix.flatten)
  }

  private def getStringVector(arg: V, loc: SourceLocation): Vector[String] = {
    getWdlVector(arg, loc).map(value => EvalUtils.formatPrimitive(value, loc))
  }

  // Array[String] prefix(String, Array[X])
  //
  // Given a String and an Array[X] where X is a primitive type, the
  // prefix function returns an array of strings comprised of each
  // element of the input array prefixed by the specified prefix
  // string.
  //
  // since: draft-2
  private def prefix(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val prefix = getWdlString(x, ctx.loc)
    val array = getStringVector(y, ctx.loc)
    V_Array(array.map(s => V_String(s"${prefix}${s}")))
  }

  // Array[String] suffix(String, Array[X])
  //
  // Given a String and an Array[X] where X is a primitive type, the
  // suffix function returns an array of strings comprised of each
  // element of the input array suffixed by the specified suffix
  // string.
  //
  // since: version 2
  private def suffix(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val suffix = getWdlString(x, ctx.loc)
    val array = getStringVector(y, ctx.loc)
    V_Array(array.map(s => V_String(s"${s}${suffix}")))
  }

  // Array[String] quote(Array[X])
  //
  // since: version 2
  private def quote(ctx: FunctionContext): V_Array = {
    val array = getStringVector(ctx.getOneArg, ctx.loc)
    V_Array(array.map(str => V_String(s"${'"'}${str}${'"'}")))
  }

  // Array[String] squote(Array[X])
  //
  // since: version 2
  private def squote(ctx: FunctionContext): V_Array = {
    val array = getStringVector(ctx.getOneArg, ctx.loc)
    V_Array(array.map(s => V_String(s"'${s}'")))
  }

  // String sep(String, Array[String])
  //
  // since: version 2
  private def sep(ctx: FunctionContext): V_String = {
    val (x, y) = ctx.getTwoArgs
    val separator = getWdlString(x, ctx.loc)
    val strings = getWdlVector(y, ctx.loc).map(x => getWdlString(x, ctx.loc))
    V_String(strings.mkString(separator))
  }

  // Array[Pair[X,Y]] as_pairs(Map[X,Y])
  //
  // since: version 2
  private def as_pairs(ctx: FunctionContext): V_Array = {
    val map = getWdlMap(ctx.getOneArg, ctx.loc)
    V_Array(map.map {
      case (key, value) =>
        V_Pair(key, value)
    }.toVector)
  }

  // Map[X,Y] as_map(Array[Pair[X,Y]])
  //
  // since: version 2
  private def as_map(ctx: FunctionContext): V_Map = {
    V_Map(getWdlVector(ctx.getOneArg, ctx.loc).foldLeft(TreeSeqMap.empty[V, V]) {
      case (accu, item) =>
        val (key, value) = getWdlPair(item, ctx.loc)
        if (accu.contains(key)) {
          throw new EvalException(s"as_map:  key collision ${key}", ctx.loc)
        }
        accu + (key -> value)
    })
  }

  // Array[X] keys(Map[X,Y])
  //
  // since: version 2
  private def keys(ctx: FunctionContext): V_Array = {
    V_Array(getWdlMap(ctx.getOneArg, ctx.loc).keys.toVector)
  }

  // Map[X,Array[Y]] collect_by_key(Array[Pair[X,Y]])
  //
  // since: version 2
  private def collect_by_key(ctx: FunctionContext): V_Map = {
    val array: Vector[(V, V)] =
      getWdlVector(ctx.getOneArg, ctx.loc).map(item => getWdlPair(item, ctx.loc))
    val (orderedKeys, items) = array.foldLeft(Vector.empty[V], Map.empty[V, Vector[V]]) {
      case ((keys, accu), (k, v)) if keys.contains(k) =>
        (keys, accu + (k -> (accu(k) :+ v)))
      case ((keys, accu), (k, v)) =>
        (keys :+ k, accu + (k -> Vector(v)))
    }
    V_Map(orderedKeys.map(key => key -> V_Array(items(key))).to(TreeSeqMap))
  }

  // Boolean defined(X?)
  //
  // since: draft-2
  private def defined(ctx: FunctionContext): V = {
    EvalUtils.unwrapOptional(ctx.getOneArg) match {
      case V_Null => V_Boolean(false)
      case _      => V_Boolean(true)
    }
  }

  // Array[X] select_all(Array[X?])
  //
  // since: draft-2
  private def select_all(ctx: FunctionContext): V = {
    val array = getWdlVector(ctx.getOneArg, ctx.loc)
    val values = array.flatMap {
      case V_Null => None
      case x      => Some(EvalUtils.unwrapOptional(x))
    }
    V_Array(values)
  }

  // X select_first(Array[X?])
  //
  // since: draft-2
  private def select_first(ctx: FunctionContext): V = {
    val array = getWdlVector(ctx.getOneArg, ctx.loc)
    val values = array.flatMap {
      case V_Null => None
      case x      => Some(EvalUtils.unwrapOptional(x))
    }
    values.headOption.getOrElse(
        throw new EvalException("select_first: found no non-null elements", ctx.loc)
    )
  }
}
