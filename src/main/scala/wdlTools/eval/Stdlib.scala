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
import wdlTools.types.ExprState
import wdlTools.types.ExprState.ExprState
import wdlTools.types.WdlTypes.{T_Boolean, T_File, T_Int, _}

import scala.io.Source

case class Stdlib(paths: EvalPaths,
                  version: WdlVersion,
                  fileResolver: FileSourceResolver = FileSourceResolver.get,
                  logger: Logger = Logger.get) {
  private case class FunctionContext(args: Vector[WdlValues.V],
                                     exprState: ExprState,
                                     loc: SourceLocation) {
    def assertNoArgs(): Unit = {
      if (args.nonEmpty) {
        throw new EvalException(s"Invalid arguments ${args}, expected none", loc)
      }
    }

    def getOneArg: V = {
      args match {
        case Vector(arg) => arg
        case _ =>
          throw new EvalException(s"Invalid arguments ${args}, expected exactly one", loc)
      }
    }

    def getTwoArgs: (V, V) = {
      args match {
        case Vector(arg1, arg2) =>
          (arg1, arg2)
        case _ =>
          throw new EvalException(s"Invalid arguments ${args}, expected exactly two", loc)
      }
    }

    def getThreeArgs: (V, V, V) = {
      args match {
        case Vector(arg1, arg2, arg3) =>
          (arg1, arg2, arg3)
        case _ =>
          throw new EvalException(s"Invalid arguments ${args}, expected exactly three", loc)
      }
    }
  }

  private type FunctionImpl = FunctionContext => V

  private val ioSupport: IoSupport = IoSupport(paths, fileResolver, logger)

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
      Builtins.UnaryMinus -> unaryMinus,
      Builtins.UnaryMinus -> unaryPlus,
      Builtins.LogicalNot -> logicalNot,
      Builtins.LogicalAnd -> logicalAnd,
      Builtins.LogicalOr -> logicalOr,
      Builtins.Equality -> equality,
      Builtins.Inequality -> inequality,
      Builtins.LessThan -> lessThan,
      Builtins.LessThanOrEqual -> lessThanOrEqual,
      Builtins.GreaterThan -> greaterThan,
      Builtins.GreaterThanOrEqual -> greaterThanOrEqual,
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
      Builtins.UnaryMinus -> unaryMinus,
      Builtins.UnaryMinus -> unaryPlus,
      Builtins.LogicalNot -> logicalNot,
      Builtins.LogicalAnd -> logicalAnd,
      Builtins.LogicalOr -> logicalOr,
      Builtins.Equality -> equality,
      Builtins.Inequality -> inequality,
      Builtins.LessThan -> lessThan,
      Builtins.LessThanOrEqual -> lessThanOrEqual,
      Builtins.GreaterThan -> greaterThan,
      Builtins.GreaterThanOrEqual -> greaterThanOrEqual,
      Builtins.Stdout -> stdout,
      Builtins.Stderr -> stderr,
      // read from a file
      Builtins.ReadLines -> read_lines,
      Builtins.ReadTsv -> read_tsv,
      Builtins.ReadMap -> read_map,
      Builtins.ReadJson -> read_json,
      Builtins.ReadInt -> read_int,
      Builtins.ReadString -> read_string,
      Builtins.ReadFloat -> read_float,
      Builtins.ReadBoolean -> read_boolean,
      // write to a file
      Builtins.WriteLines -> write_lines,
      Builtins.WriteTsv -> write_tsv,
      Builtins.WriteMap -> write_map,
      Builtins.WriteJson -> write_json,
      // other functions
      Builtins.Size -> size,
      Builtins.Sub -> sub,
      Builtins.Range -> range,
      Builtins.Transpose -> transpose,
      Builtins.Zip -> zip,
      Builtins.Cross -> cross,
      Builtins.AsPairs -> as_pairs,
      Builtins.AsMap -> as_map,
      Builtins.Keys -> keys,
      Builtins.CollectByKey -> collect_by_key,
      Builtins.Length -> length,
      Builtins.Flatten -> flatten,
      Builtins.Prefix -> prefix,
      Builtins.Suffix -> suffix,
      Builtins.Quote -> quote,
      Builtins.Squote -> squote,
      Builtins.SelectFirst -> select_first,
      Builtins.SelectAll -> select_all,
      Builtins.Defined -> defined,
      Builtins.Basename -> basename,
      Builtins.Floor -> floor,
      Builtins.Ceil -> ceil,
      Builtins.Round -> round,
      Builtins.Sep -> sep,
      Builtins.Min -> min,
      Builtins.Max -> max,
      Builtins.Glob -> glob
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
           exprState: ExprState = ExprState.Start): V = {
    if (!funcTable.contains(funcName)) {
      throw new EvalException(s"stdlib function ${funcName} not implemented", loc)
    }
    val ctx = FunctionContext(args, exprState, loc)
    val impl = funcTable(funcName)
    try {
      impl(ctx)
    } catch {
      case e: EvalException =>
        throw e
      case e: Throwable =>
        val msg = s"""|calling stdlib function ${funcName} with arguments ${args}
                      |${e.getMessage}
                      |""".stripMargin
        throw new EvalException(msg, loc)
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

  private def getWdlMap(value: V, loc: SourceLocation): Map[V, V] = {
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
        case (V_Null, V_Null)               => true
        case (V_Int(n1), V_Int(n2))         => n1 == n2
        case (V_Float(x1), V_Int(n2))       => x1 == n2
        case (V_Int(n1), V_Float(x2))       => n1 == x2
        case (V_Float(x1), V_Float(x2))     => x1 == x2
        case (V_String(s1), V_String(s2))   => s1 == s2
        case (V_Boolean(b1), V_Boolean(b2)) => b1 == b2
        case (V_File(f1), V_File(f2))       => f1 == f2
        case (V_File(p1), V_String(p2))     => p1 == p2
        case (V_Pair(l1, r1), V_Pair(l2, r2)) =>
          inner(l1, l2) && inner(r1, r2)
        case (V_Array(a1), V_Array(a2)) if a1.size != a2.size => false
        case (V_Array(a1), V_Array(a2))                       =>
          // All array elements must be equal
          a1.zip(a2).forall {
            case (x, y) => inner(x, y)
          }
        case (V_Map(m1), V_Map(m2)) if m1.size != m2.size => false
        case (V_Map(m1), V_Map(m2)) =>
          val keysEqual = m1.keySet.zip(m2.keySet).forall {
            case (k1, k2) => inner(k1, k2)
          }
          if (!keysEqual) {
            false
          } else {
            // now we know the keys are all equal
            m1.keys.forall(k => inner(m1(k), m2(k)))
          }
        case (V_Optional(v1), V_Optional(v2)) =>
          inner(v1, v2)
        case (V_Optional(v1), v2) =>
          inner(v1, v2)
        case (v1, V_Optional(v2)) =>
          inner(v1, v2)
        case (V_Struct(name1, _), V_Struct(name2, _)) if name1 != name2 => false
        case (V_Struct(name, members1), V_Struct(_, members2))
            if members1.keySet != members2.keySet =>
          // We need the type definition here. The other option is to assume it has already
          // been cleared at compile time.
          throw new EvalException(s"""struct ${name} does not have the correct number of members:
                                     |${members1.size} != ${members2.size}""".stripMargin, ctx.loc)
        case (V_Struct(_, members1), V_Struct(_, members2)) =>
          members1.keys.forall(k => inner(members1(k), members2(k)))
        case (V_Object(members1), V_Object(members2)) if members1.keySet != members2.keySet => false
        case (V_Object(members1), V_Object(members2)) =>
          members1.forall {
            case (k, v) => inner(v, members2(k))
          }
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
    ctx.getTwoArgs match {
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
      case (V_Null, _) if ctx.exprState >= ExprState.InPlaceholder =>
        V_Null
      case (_, V_Null) if ctx.exprState >= ExprState.InPlaceholder =>
        V_Null
      case (V_Optional(v1), V_Optional(v2)) if ctx.exprState >= ExprState.InPlaceholder =>
        V_Optional(addition(ctx.copy(Vector(v1, v2))))
      case (V_Optional(v1), v2) if ctx.exprState >= ExprState.InPlaceholder =>
        V_Optional(addition(ctx.copy(Vector(v1, v2))))
      case (v1, V_Optional(v2)) if ctx.exprState >= ExprState.InPlaceholder =>
        V_Optional(addition(ctx.copy(Vector(v1, v2))))
      case other =>
        throw new EvalException(s"cannot add values ${other}", ctx.loc)
    }
  }

  private def subtraction(ctx: FunctionContext): V = {
    ctx.getTwoArgs match {
      case (V_Int(n1), V_Int(n2))     => V_Int(n1 - n2)
      case (V_Float(x1), V_Int(n2))   => V_Float(x1 - n2)
      case (V_Int(n1), V_Float(x2))   => V_Float(n1 - x2)
      case (V_Float(x1), V_Float(x2)) => V_Float(x1 - x2)
      case other =>
        throw new EvalException(
            s"cannot subtract values ${other}; arguments must be integers or floats",
            ctx.loc
        )
    }
  }

  private def multiplication(ctx: FunctionContext): V = {
    ctx.getTwoArgs match {
      case (V_Int(n1), V_Int(n2))     => V_Int(n1 * n2)
      case (V_Float(x1), V_Int(n2))   => V_Float(x1 * n2)
      case (V_Int(n1), V_Float(x2))   => V_Float(n1 * x2)
      case (V_Float(x1), V_Float(x2)) => V_Float(x1 * x2)
      case other =>
        throw new EvalException(
            s"cannot multiply values ${other}; arguments must be integers or floats",
            ctx.loc
        )
    }
  }

  private def division(ctx: FunctionContext): V = {
    ctx.getTwoArgs match {
      case (_, denominator: V_Numeric) if denominator.floatValue == 0 =>
        throw new EvalException("DivisionByZero", ctx.loc)
      case (V_Int(n1), V_Int(n2))     => V_Int(n1 / n2)
      case (V_Float(x1), V_Int(n2))   => V_Float(x1 / n2)
      case (V_Int(n1), V_Float(x2))   => V_Float(n1 / x2)
      case (V_Float(x1), V_Float(x2)) => V_Float(x1 / x2)
      case other =>
        throw new EvalException(
            s"cannot divide values ${other}; arguments must be integers or floats",
            ctx.loc
        )
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
          .toMap
    )
  }

  // Object read_object(String|File)
  //
  // since: draft-1
  // deprecation:
  // * beginning in draft-2, URI parameter is not supported
  // * removed in Version 2
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
      case Vector(keys, values) => kvToObject(keys, values, ctx.loc)
      case _ =>
        throw new EvalException(
            s"read_object : file ${file.toString} must contain exactly two lines",
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
      throw new EvalException(s"read_object : file ${file.toString} must contain at least two",
                              ctx.loc)
    }
    val keys = lines.head
    V_Array(lines.tail.map(values => kvToObject(keys, values, ctx.loc)))
  }

  private def kvToObject(keys: Vector[String],
                         values: Vector[String],
                         loc: SourceLocation): V_Object = {
    if (keys.size != values.size) {
      throw new EvalException(
          s"""read_object : the number of keys (${keys.size})
             |must be the same as the number of values (${values.size})""".stripMargin
            .replace("\t", " "),
          loc
      )
    }
    // Note all the values are going to be strings here. This probably isn't what the user wants.
    val m = (keys zip values).map {
      case (k, v) =>
        k -> V_String(v)
    }.toMap
    V_Object(m)
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
      case e: JsonSerializationException =>
        throw new EvalException(e.getMessage, ctx.loc)
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
        throw new EvalException(s"could not convert (${content}) to an integer", ctx.loc)
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
    val lines = content.split("\n")
    if (lines.isEmpty) {
      // There are no lines in the file, should we throw an exception instead?
      V_String("")
    } else {
      V_String(lines.head)
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
        throw new EvalException(s"could not convert (${content}) to a float", ctx.loc)
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
        throw new EvalException(s"could not convert (${content}) to a boolean", ctx.loc)
    }
  }

  // File write_lines(Array[String])
  //
  // since: draft-1
  private def write_lines(ctx: FunctionContext): V_File = {
    val array: V_Array =
      Coercion.coerceTo(T_Array(T_String), ctx.getOneArg, ctx.loc).asInstanceOf[V_Array]
    val strRepr: String = array.value
      .map {
        case V_String(x) => x
        case other =>
          throw new EvalException(s"write_lines: element ${other} should be a string", ctx.loc)
      }
      // note: '\n' line endings explicitly specified in the spec
      .mkString("\n")
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    ioSupport.writeFile(tmpFile, strRepr, ctx.loc)
    V_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  // since: draft-1
  private def write_tsv(ctx: FunctionContext): V_File = {
    val arAr: V_Array =
      Coercion.coerceTo(T_Array(T_Array(T_String)), ctx.getOneArg, ctx.loc).asInstanceOf[V_Array]
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      arAr.value
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
      Coercion.coerceTo(T_Map(T_String, T_String), ctx.getOneArg, ctx.loc).asInstanceOf[V_Map]
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

  private def lineFromObject(obj: V_Object, loc: SourceLocation): Vector[String] = {
    obj.members.values.map { vw =>
      Coercion.coerceTo(T_String, vw, loc).asInstanceOf[V_String].value
    }.toVector
  }

  // File write_object(Object)
  //
  // since: draft-1
  // deprecation: removed in Version 2
  private def write_object(ctx: FunctionContext): V_File = {
    val obj = Coercion.coerceTo(T_Object, ctx.getOneArg, ctx.loc).asInstanceOf[V_Object]
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(obj.members.keys.toVector)
      writer.write(lineFromObject(obj, ctx.loc))
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
    val objs = Coercion.coerceTo(T_Array(T_Object), ctx.getOneArg, ctx.loc).asInstanceOf[V_Array]
    val objArray = objs.value.asInstanceOf[Vector[V_Object]]
    if (objArray.isEmpty) {
      throw new EvalException("write_objects: empty input array", ctx.loc)
    }

    // check that all objects have the same keys
    val fstObj = objArray(0)
    val keys = fstObj.members.keys.toSet
    objArray.tail.foreach { obj =>
      if (obj.members.keys.toSet != keys)
        throw new EvalException(
            "write_objects: the keys are not the same for all objects in the array",
            ctx.loc
        )
    }

    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".txt")
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(fstObj.members.keys.toVector)
      objArray.foreach(obj => writer.write(lineFromObject(obj, ctx.loc)))
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
        case e: JsonSerializationException =>
          throw new EvalException(e.getMessage, ctx.loc)
      }
    val tmpFile: Path = ioSupport.mkTempFile(suffix = ".json")
    ioSupport.writeFile(tmpFile, jsv.prettyPrint, ctx.loc)
    V_File(tmpFile.toString)
  }

  // Our size implementation is a little bit more general than strictly necessary.
  //
  // recursively dive into the entire structure and sum up all the file sizes. Strings
  // are coerced into file paths.
  private def sizeCoreInner(arg: V, loc: SourceLocation): Double = {
    arg match {
      case V_String(path) => ioSupport.size(path, loc).toDouble
      case V_File(path)   => ioSupport.size(path, loc).toDouble
      case V_Array(ar) =>
        ar.foldLeft(0.0) {
          case (accu, wv) => accu + sizeCoreInner(wv, loc)
        }
      case V_Optional(value) => sizeCoreInner(value, loc)
      case _                 => 0
    }
  }

  private def sizeCore(arg: V, loc: SourceLocation): Double = {
    try {
      sizeCoreInner(arg, loc)
    } catch {
      case e: EvalException =>
        throw e
      case e: Throwable =>
        throw new EvalException(s"size(${arg}  msg=${e.getMessage})", loc)
    }
  }

  // Size can take several kinds of arguments.
  //
  // since: draft-2
  // version differences: in 1.0, the spec was updated to explicitly support compount argument types;
  //  however, our implementation supports compound types for all versions, and goes further than the
  //  spec requires to support nested collections.
  private def size(ctx: FunctionContext): V_Float = {
    ctx.args.size match {
      case 1 =>
        V_Float(sizeCore(ctx.args.head, ctx.loc))
      case 2 =>
        val sUnit = getWdlString(ctx.args(1), ctx.loc)
        val nBytesInUnit = EvalUtils.getSizeMultiplier(sUnit, ctx.loc)
        val nBytes = sizeCore(ctx.args.head, ctx.loc)
        V_Float(nBytes / nBytesInUnit)
      case _ =>
        throw new EvalException(
            "size: called with wrong number of arguments",
            ctx.loc
        )
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

  // Array[Int] range(Int)
  //
  // since: draft-2
  private def range(ctx: FunctionContext): V_Array = {
    val n = getWdlInt(ctx.getOneArg, ctx.loc).toInt
    val vec: Vector[V] = Vector.tabulate(n)(i => V_Int(i))
    V_Array(vec)
  }

  // Array[Array[X]] transpose(Array[Array[X]])
  //
  // since: draft-2
  private def transpose(ctx: FunctionContext): V_Array = {
    val vec: Vector[V] = getWdlVector(ctx.getOneArg, ctx.loc)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, ctx.loc))
    val trValue = vec_vec.transpose
    V_Array(trValue.map(vec => V_Array(vec)))
  }

  // Array[Pair(X,Y)] zip(Array[X], Array[Y])
  //
  // since: draft-2
  private def zip(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val ax = getWdlVector(x, ctx.loc)
    val ay = getWdlVector(y, ctx.loc)
    V_Array((ax zip ay).map {
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
    val ax = getWdlVector(x, ctx.loc)
    val ay = getWdlVector(y, ctx.loc)
    val vv = ax.map { x =>
      ay.map { y =>
        V_Pair(x, y)
      }
    }
    V_Array(vv.flatten)
  }

  // Array[Pair[X,Y]] as_pairs(Map[X,Y])
  //
  // since: V2
  private def as_pairs(ctx: FunctionContext): V_Array = {
    val map = getWdlMap(ctx.getOneArg, ctx.loc)
    V_Array(map.map {
      case (key, value) => V_Pair(key, value)
    }.toVector)
  }

  // Map[X,Y] as_map(Array[Pair[X,Y]])
  //
  // since: V2
  private def as_map(ctx: FunctionContext): V_Map = {
    val vec = getWdlVector(ctx.getOneArg, ctx.loc)
    V_Map(vec.map(item => getWdlPair(item, ctx.loc)).toMap)
  }

  // Array[X] keys(Map[X,Y])
  //
  // since: V2
  private def keys(ctx: FunctionContext): V_Array = {
    val map = getWdlMap(ctx.getOneArg, ctx.loc)
    V_Array(map.keys.toVector)
  }

  // Map[X,Array[Y]] collect_by_key(Array[Pair[X,Y]])
  //
  // since: V2
  private def collect_by_key(ctx: FunctionContext): V_Map = {
    val vec: Vector[(V, V)] =
      getWdlVector(ctx.getOneArg, ctx.loc).map(item => getWdlPair(item, ctx.loc))
    V_Map(
        vec.groupBy(_._1).map {
          case (key, values: Vector[(V, V)]) => key -> V_Array(values.map(_._2))
        }
    )
  }

  // Integer length(Array[X])
  //
  // since: draft-2
  private def length(ctx: FunctionContext): V_Int = {
    val vec = getWdlVector(ctx.getOneArg, ctx.loc)
    V_Int(vec.size)
  }

  // Array[X] flatten(Array[Array[X]])
  //
  // since: draft-2
  private def flatten(ctx: FunctionContext): V_Array = {
    val vec: Vector[V] = getWdlVector(ctx.getOneArg, ctx.loc)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, ctx.loc))
    V_Array(vec_vec.flatten)
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
    val pref = getWdlString(x, ctx.loc)
    val vec = getStringVector(y, ctx.loc)
    V_Array(vec.map(str => V_String(pref + str)))
  }

  // Array[String] suffix(String, Array[X])
  //
  // Given a String and an Array[X] where X is a primitive type, the
  // suffix function returns an array of strings comprised of each
  // element of the input array suffixed by the specified suffix
  // string.
  //
  // since: V2
  private def suffix(ctx: FunctionContext): V_Array = {
    val (x, y) = ctx.getTwoArgs
    val suff = getWdlString(x, ctx.loc)
    val vec = getStringVector(y, ctx.loc)
    V_Array(vec.map(str => V_String(str + suff)))
  }

  // Array[String] quote(Array[X])
  //
  // since: V2
  private def quote(ctx: FunctionContext): V_Array = {
    val vec = getStringVector(ctx.getOneArg, ctx.loc)
    val dquote = '"'
    V_Array(vec.map(str => V_String(s"${dquote}${str}${dquote}")))
  }

  // Array[String] squote(Array[X])
  //
  // since: V2
  private def squote(ctx: FunctionContext): V_Array = {
    val vec = getStringVector(ctx.getOneArg, ctx.loc)
    V_Array(vec.map(str => V_String(s"'${str}'")))
  }

  private def getStringVector(arg: V, loc: SourceLocation): Vector[String] = {
    getWdlVector(arg, loc).map(vw => EvalUtils.formatPrimitive(vw, loc))
  }

  // X select_first(Array[X?])
  //
  // return the first none null element. Throw an exception if nothing is found
  //
  // since: draft-2
  private def select_first(ctx: FunctionContext): V = {
    val vec = getWdlVector(ctx.getOneArg, ctx.loc)
    val values = vec.flatMap {
      case V_Null        => None
      case V_Optional(x) => Some(x)
      case x             => Some(x)
    }
    if (values.isEmpty)
      throw new EvalException("select_first: found no non-null elements", ctx.loc)
    values.head
  }

  // Array[X] select_all(Array[X?])
  //
  // since: draft-2
  private def select_all(ctx: FunctionContext): V = {
    val vec = getWdlVector(ctx.getOneArg, ctx.loc)
    val values = vec.flatMap {
      case V_Null        => None
      case V_Optional(x) => Some(x)
      case x             => Some(x)
    }
    V_Array(values)
  }

  // Boolean defined(X?)
  //
  // since: draft-2
  private def defined(ctx: FunctionContext): V = {
    ctx.getOneArg match {
      case V_Null => V_Boolean(false)
      case _      => V_Boolean(true)
    }
  }

  private def basenameCore(arg: V, loc: SourceLocation): String = {
    val filePath = arg match {
      case V_File(s)   => s
      case V_String(s) => s
      case other =>
        throw new EvalException(s"${other} must be a string or a file type", loc)
    }
    Paths.get(filePath).getFileName.toString
  }

  // String basename(String)
  //
  // This function returns the basename of a file path passed to it: basename("/path/to/file.txt") returns "file.txt".
  // Also supports an optional parameter, suffix to remove: basename("/path/to/file.txt", ".txt") returns "file".
  //
  private def basename(ctx: FunctionContext): V_String = {
    ctx.args.size match {
      case 1 =>
        val s = basenameCore(ctx.args.head, ctx.loc)
        V_String(s)
      case 2 =>
        val s = basenameCore(ctx.args(0), ctx.loc)
        val suff = getWdlString(ctx.args(1), ctx.loc)
        V_String(s.stripSuffix(suff))
      case _ =>
        throw new EvalException(s"basename: wrong number of arguments", ctx.loc)
    }
  }

  private def floor(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.floor(x).toInt)
  }

  private def ceil(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.ceil(x).toInt)
  }

  private def round(ctx: FunctionContext): V_Int = {
    val x = getWdlFloat(ctx.getOneArg, ctx.loc)
    V_Int(Math.round(x).toInt)
  }

  private def glob(ctx: FunctionContext): V_Array = {
    val pattern = getWdlString(ctx.getOneArg, ctx.loc)
    val filenames = ioSupport.glob(pattern)
    V_Array(filenames.map { filepath =>
      V_File(filepath)
    })
  }

  private def sep(ctx: FunctionContext): V_String = {
    val (x, y) = ctx.getTwoArgs
    val separator = getWdlString(x, ctx.loc)
    val strings = getWdlVector(y, ctx.loc).map(x => getWdlString(x, ctx.loc))
    V_String(strings.mkString(separator))
  }

  private def min(ctx: FunctionContext): V_Numeric = {
    ctx.getTwoArgs match {
      case (V_Int(x), V_Int(y))     => V_Int(Math.min(x, y))
      case (V_Int(x), V_Float(y))   => V_Float(Math.min(x.toDouble, y))
      case (V_Float(x), V_Int(y))   => V_Float(Math.min(x, y.toDouble))
      case (V_Float(x), V_Float(y)) => V_Float(Math.min(x, y))
      case other =>
        throw new RuntimeException(s"Cannot apply max() to ${other}")
    }
  }

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
}
