package wdlTools.eval

import java.nio.file.{Path, Paths}

import kantan.csv.CsvConfiguration.{Header, QuotePolicy}
import kantan.csv._
import kantan.csv.ops._
import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.WdlTypes._
import wdlTools.util.Options

import scala.io.Source

case class Stdlib(opts: Options, evalCfg: EvalConfig, version: WdlVersion) {
  type FunctionImpl = (Vector[WdlValues.V], SourceLocation) => V

  protected val iosp: IoSupp = IoSupp(opts, evalCfg)

  private val draft2FuncTable: Map[String, FunctionImpl] = Map(
      "stdout" -> stdout,
      "stderr" -> stderr,
      // read from a file
      "read_lines" -> read_lines,
      "read_tsv" -> read_tsv,
      "read_map" -> read_map,
      "read_object" -> read_object,
      "read_objects" -> read_objects,
      "read_json" -> read_json,
      "read_int" -> read_int,
      "read_string" -> read_string,
      "read_float" -> read_float,
      "read_boolean" -> read_boolean,
      // write to a file
      "write_lines" -> write_lines,
      "write_tsv" -> write_tsv,
      "write_map" -> write_map,
      "write_object" -> write_object,
      "write_objects" -> write_objects,
      "write_json" -> write_json,
      // other functions
      "size" -> size,
      "sub" -> sub,
      "range" -> range,
      "transpose" -> transpose,
      "zip" -> zip,
      "cross" -> cross,
      "length" -> length,
      "flatten" -> flatten,
      "prefix" -> prefix,
      "select_first" -> select_first,
      "select_all" -> select_all,
      "defined" -> defined,
      "basename" -> basename,
      "floor" -> floor,
      "ceil" -> ceil,
      "round" -> round,
      "glob" -> glob
  )

  val v1FuncTable: Map[String, FunctionImpl] = Map(
      "stdout" -> stdout,
      "stderr" -> stderr,
      // read from a file
      "read_lines" -> read_lines,
      "read_tsv" -> read_tsv,
      "read_map" -> read_map,
      "read_object" -> read_object,
      "read_objects" -> read_objects,
      "read_json" -> read_json,
      "read_int" -> read_int,
      "read_string" -> read_string,
      "read_float" -> read_float,
      "read_boolean" -> read_boolean,
      // write to a file
      "write_lines" -> write_lines,
      "write_tsv" -> write_tsv,
      "write_map" -> write_map,
      "write_object" -> write_object,
      "write_objects" -> write_objects,
      "write_json" -> write_json,
      // other functions
      "size" -> size,
      "sub" -> sub,
      "range" -> range,
      "transpose" -> transpose,
      "zip" -> zip,
      "cross" -> cross,
      "length" -> length,
      "flatten" -> flatten,
      "prefix" -> prefix,
      "select_first" -> select_first,
      "select_all" -> select_all,
      "defined" -> defined,
      "basename" -> basename,
      "floor" -> floor,
      "ceil" -> ceil,
      "round" -> round,
      "glob" -> glob
  )

  val v2FuncTable: Map[String, FunctionImpl] = Map(
      "stdout" -> stdout,
      "stderr" -> stderr,
      // read from a file
      "read_lines" -> read_lines,
      "read_tsv" -> read_tsv,
      "read_map" -> read_map,
      "read_json" -> read_json,
      "read_int" -> read_int,
      "read_string" -> read_string,
      "read_float" -> read_float,
      "read_boolean" -> read_boolean,
      // write to a file
      "write_lines" -> write_lines,
      "write_tsv" -> write_tsv,
      "write_map" -> write_map,
      "write_json" -> write_json,
      // other functions
      "size" -> size,
      "sub" -> sub,
      "range" -> range,
      "transpose" -> transpose,
      "zip" -> zip,
      "cross" -> cross,
      "as_pairs" -> as_pairs,
      "as_map" -> as_map,
      "keys" -> keys,
      "collect_by_key" -> collect_by_key,
      "length" -> length,
      "flatten" -> flatten,
      "prefix" -> prefix,
      "suffix" -> suffix,
      "quote" -> quote,
      "squote" -> squote,
      "select_first" -> select_first,
      "select_all" -> select_all,
      "defined" -> defined,
      "basename" -> basename,
      "floor" -> floor,
      "ceil" -> ceil,
      "round" -> round,
      "glob" -> glob,
      "sep" -> sep
  )

  // choose the standard library prototypes according to the WDL version
  private val funcTable: Map[String, FunctionImpl] = version match {
    case WdlVersion.Draft_2 => draft2FuncTable
    case WdlVersion.V1      => v1FuncTable
    case WdlVersion.V2      => v2FuncTable
    case other              => throw new RuntimeException(s"Unsupported WDL version ${other}")
  }

  def call(funcName: String, args: Vector[V], loc: SourceLocation): V = {
    if (!(funcTable contains funcName))
      throw new EvalException(s"stdlib function ${funcName} not implemented", loc)
    val impl = funcTable(funcName)
    try {
      impl(args, loc)
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

  protected def getWdlFile(args: Vector[V], loc: SourceLocation): V_File = {
    // process arguments
    assert(args.size == 1)
    Coercion.coerceTo(T_File, args.head, loc).asInstanceOf[V_File]
  }

  protected def getWdlInt(arg: V, loc: SourceLocation): Long = {
    val n = Coercion.coerceTo(T_Int, arg, loc).asInstanceOf[V_Int]
    n.value
  }

  protected def getWdlFloat(arg: V, loc: SourceLocation): Double = {
    val x = Coercion.coerceTo(T_Float, arg, loc).asInstanceOf[V_Float]
    x.value
  }

  protected def getWdlString(arg: V, loc: SourceLocation): String = {
    val s = Coercion.coerceTo(T_String, arg, loc).asInstanceOf[V_String]
    s.value
  }

  protected def getWdlVector(value: V, loc: SourceLocation): Vector[V] = {
    value match {
      case V_Array(ar) => ar
      case other       => throw new EvalException(s"${other} should be an array", loc)
    }
  }

  protected def getWdlMap(value: V, loc: SourceLocation): Map[V, V] = {
    value match {
      case V_Map(m) => m
      case other    => throw new EvalException(s"${other} should be a map", loc)
    }
  }

  protected def getWdlPair(value: V, loc: SourceLocation): (V, V) = {
    value match {
      case V_Pair(l, r) => (l, r)
      case other        => throw new EvalException(s"${other} should be a pair", loc)
    }
  }

  // since: draft-1
  protected def stdout(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.isEmpty)
    iosp.ensureFileExists(evalCfg.stdout, "stdout", loc)
    V_File(evalCfg.stdout.toString)
  }

  // since: draft-1
  protected def stderr(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.isEmpty)
    iosp.ensureFileExists(evalCfg.stderr, "stderr", loc)
    V_File(evalCfg.stdout.toString)
  }

  // Array[String] read_lines(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_lines(args: Vector[V], loc: SourceLocation): V_Array = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    V_Array(Source.fromString(content).getLines.map(x => V_String(x)).toVector)
  }

  private val tsvConf = CsvConfiguration('\t', '"', QuotePolicy.WhenNeeded, Header.None)

  // Array[Array[String]] read_tsv(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_tsv(args: Vector[V], loc: SourceLocation): V_Array = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    val reader = content.asCsvReader[Vector[String]](tsvConf)
    V_Array(reader.map {
      case Left(err) =>
        throw new EvalException(s"Invalid tsv file ${file}: ${err}", loc)
      case Right(row) => V_Array(row.map(x => V_String(x)))
    }.toVector)
  }

  // Map[String, String] read_map(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_map(args: Vector[V], loc: SourceLocation): V_Map = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    val reader = content.asCsvReader[(String, String)](tsvConf)
    V_Map(
        reader
          .map {
            case Left(err) =>
              throw new EvalException(s"Invalid tsv file ${file}: ${err}", loc)
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
  protected def read_object(args: Vector[V], loc: SourceLocation): V_Object = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    val lines: Vector[Vector[String]] = content
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    lines match {
      case Vector(keys, values) => kvToObject(keys, values, loc)
      case _ =>
        throw new EvalException(
            s"read_object : file ${file.toString} must contain exactly two lines",
            loc
        )
    }
  }

  // Array[Object] read_objects(String|File)
  //
  // since: draft-1
  // deprecation:
  // * beginning in draft-2, URI parameter is not supported
  // * removed in Version 2
  protected def read_objects(args: Vector[V], loc: SourceLocation): V_Array = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    val lines = content
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    if (lines.size < 2) {
      throw new EvalException(s"read_object : file ${file.toString} must contain at least two", loc)
    }
    val keys = lines.head
    V_Array(lines.tail.map(values => kvToObject(keys, values, loc)))
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
  protected def read_json(args: Vector[V], loc: SourceLocation): V = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    try {
      Serialize.fromJson(content.parseJson)
    } catch {
      case e: JsonSerializationException =>
        throw new EvalException(e.getMessage, loc)
    }
  }

  // Int read_int(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_int(args: Vector[V], loc: SourceLocation): V_Int = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    try {
      V_Int(content.trim.toInt)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to an integer", loc)
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
  protected def read_string(args: Vector[V], loc: SourceLocation): V_String = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
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
  protected def read_float(args: Vector[V], loc: SourceLocation): V_Float = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    try {
      V_Float(content.trim.toDouble)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to a float", loc)
    }
  }

  // Boolean read_boolean(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_boolean(args: Vector[V], loc: SourceLocation): V_Boolean = {
    val file = getWdlFile(args, loc)
    val content = iosp.readFile(file.value, loc)
    content.trim.toLowerCase() match {
      case "false" => V_Boolean(false)
      case "true"  => V_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert (${content}) to a boolean", loc)
    }
  }

  // File write_lines(Array[String])
  //
  // since: draft-1
  protected def write_lines(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val array: V_Array =
      Coercion.coerceTo(T_Array(T_String), args.head, loc).asInstanceOf[V_Array]
    val strRepr: String = array.value
      .map {
        case V_String(x) => x
        case other =>
          throw new EvalException(s"write_lines: element ${other} should be a string", loc)
      }
      // note: '\n' line endings explicitly specified in the spec
      .mkString("\n")
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, strRepr, loc)
    V_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  // since: draft-1
  protected def write_tsv(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val arAr: V_Array =
      Coercion.coerceTo(T_Array(T_Array(T_String)), args.head, loc).asInstanceOf[V_Array]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      arAr.value
        .foreach {
          case V_Array(a) =>
            val row = a.map {
              case V_String(s) => s
              case other =>
                throw new EvalException(s"${other} should be a string", loc)
            }
            writer.write(row)
          case other =>
            throw new EvalException(s"${other} should be an array", loc)
        }
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_map(Map[String, String])
  //
  // since: draft-1
  protected def write_map(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val m: V_Map =
      Coercion.coerceTo(T_Map(T_String, T_String), args.head, loc).asInstanceOf[V_Map]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[(String, String)](tsvConf)
    try {
      m.value
        .foreach {
          case (V_String(key), V_String(value)) => writer.write((key, value))
          case (k, v) =>
            throw new EvalException(s"${k} ${v} should both be strings", loc)
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
  protected def write_object(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val obj = Coercion.coerceTo(T_Object, args.head, loc).asInstanceOf[V_Object]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(obj.members.keys.toVector)
      writer.write(lineFromObject(obj, loc))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_objects(Array[Object])
  //
  // since: draft-1
  // deprecation: removed in Version 2
  protected def write_objects(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val objs = Coercion.coerceTo(T_Array(T_Object), args.head, loc).asInstanceOf[V_Array]
    val objArray = objs.value.asInstanceOf[Vector[V_Object]]
    if (objArray.isEmpty) {
      throw new EvalException("write_objects: empty input array", loc)
    }

    // check that all objects have the same keys
    val fstObj = objArray(0)
    val keys = fstObj.members.keys.toSet
    objArray.tail.foreach { obj =>
      if (obj.members.keys.toSet != keys)
        throw new EvalException(
            "write_objects: the keys are not the same for all objects in the array",
            loc
        )
    }

    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(fstObj.members.keys.toVector)
      objArray.foreach(obj => writer.write(lineFromObject(obj, loc)))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_json(mixed)
  //
  // since: draft-1
  protected def write_json(args: Vector[V], loc: SourceLocation): V_File = {
    assert(args.size == 1)
    val jsv =
      try {
        Serialize.toJson(args.head)
      } catch {
        case e: JsonSerializationException =>
          throw new EvalException(e.getMessage, loc)
      }
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, jsv.prettyPrint, loc)
    V_File(tmpFile.toString)
  }

  // Our size implementation is a little bit more general than strictly necessary.
  //
  // recursively dive into the entire structure and sum up all the file sizes. Strings
  // are coerced into file paths.
  private def sizeCoreInner(arg: V, loc: SourceLocation): Double = {
    arg match {
      case V_String(path) => iosp.size(path, loc).toDouble
      case V_File(path)   => iosp.size(path, loc).toDouble
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
  protected def size(args: Vector[V], loc: SourceLocation): V_Float = {
    args.size match {
      case 1 =>
        V_Float(sizeCore(args.head, loc))
      case 2 =>
        val sUnit = getWdlString(args(1), loc)
        val nBytesInUnit = Util.sizeUnit(sUnit, loc)
        val nBytes = sizeCore(args.head, loc)
        V_Float(nBytes / nBytesInUnit)
      case _ =>
        throw new EvalException("size: called with wrong number of arguments", loc)
    }
  }

  // String sub(String, String, String)
  //
  // Given 3 String parameters input, pattern, replace, this function
  // will replace any occurrence matching pattern in input by
  // replace. pattern is expected to be a regular expression.
  //
  // since: draft-2
  protected def sub(args: Vector[V], loc: SourceLocation): V_String = {
    assert(args.size == 3)
    val input = getWdlString(args(0), loc)
    val pattern = getWdlString(args(1), loc)
    val replace = getWdlString(args(2), loc)
    V_String(input.replaceAll(pattern, replace))
  }

  // Array[Int] range(Int)
  //
  // since: draft-2
  protected def range(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val n = getWdlInt(args.head, loc).toInt
    val vec: Vector[V] = Vector.tabulate(n)(i => V_Int(i))
    V_Array(vec)
  }

  // Array[Array[X]] transpose(Array[Array[X]])
  //
  // since: draft-2
  protected def transpose(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val vec: Vector[V] = getWdlVector(args.head, loc)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, loc))
    val trValue = vec_vec.transpose
    V_Array(trValue.map(vec => V_Array(vec)))
  }

  // Array[Pair(X,Y)] zip(Array[X], Array[Y])
  //
  // since: draft-2
  protected def zip(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 2)
    val ax = getWdlVector(args(0), loc)
    val ay = getWdlVector(args(1), loc)

    V_Array((ax zip ay).map {
      case (x, y) => V_Pair(x, y)
    })
  }

  // Array[Pair(X,Y)] cross(Array[X], Array[Y])
  //
  // cartesian product of two arrays. Results in an n x m sized array of pairs.
  //
  // since: draft-2
  protected def cross(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 2)
    val ax = getWdlVector(args(0), loc)
    val ay = getWdlVector(args(1), loc)

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
  protected def as_pairs(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val map = getWdlMap(args.head, loc)
    V_Array(map.map {
      case (key, value) => V_Pair(key, value)
    }.toVector)
  }

  // Map[X,Y] as_map(Array[Pair[X,Y]])
  //
  // since: V2
  protected def as_map(args: Vector[V], loc: SourceLocation): V_Map = {
    assert(args.size == 1)
    val vec = getWdlVector(args.head, loc)
    V_Map(vec.map(item => getWdlPair(item, loc)).toMap)
  }

  // Array[X] keys(Map[X,Y])
  //
  // since: V2
  protected def keys(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val map = getWdlMap(args.head, loc)
    V_Array(map.keys.toVector)
  }

  // Map[X,Array[Y]] collect_by_key(Array[Pair[X,Y]])
  //
  // since: V2
  protected def collect_by_key(args: Vector[V], loc: SourceLocation): V_Map = {
    assert(args.size == 1)
    val vec: Vector[(V, V)] = getWdlVector(args.head, loc).map(item => getWdlPair(item, loc))
    V_Map(
        vec.groupBy(_._1).map {
          case (key, values: Vector[(V, V)]) => key -> V_Array(values.map(_._2))
        }
    )
  }

  // Integer length(Array[X])
  //
  // since: draft-2
  protected def length(args: Vector[V], loc: SourceLocation): V_Int = {
    assert(args.size == 1)
    val vec = getWdlVector(args.head, loc)
    V_Int(vec.size)
  }

  // Array[X] flatten(Array[Array[X]])
  //
  // since: draft-2
  protected def flatten(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val vec: Vector[V] = getWdlVector(args.head, loc)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, loc))
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
  protected def prefix(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 2)
    val pref = getWdlString(args(0), loc)
    val vec = getStringVector(args(1), loc)
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
  protected def suffix(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 2)
    val suff = getWdlString(args(0), loc)
    val vec = getStringVector(args(1), loc)
    V_Array(vec.map(str => V_String(str + suff)))
  }

  // Array[String] quote(Array[X])
  //
  // since: V2
  protected def quote(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val vec = getStringVector(args(1), loc)
    val dquote = '"'
    V_Array(vec.map(str => V_String(s"${dquote}${str}${dquote}")))
  }

  // Array[String] squote(Array[X])
  //
  // since: V2
  protected def squote(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val vec = getStringVector(args(1), loc)
    V_Array(vec.map(str => V_String(s"'${str}'")))
  }

  private def getStringVector(arg: V, loc: SourceLocation): Vector[String] = {
    getWdlVector(arg, loc).map(vw => Serialize.primitiveValueToString(vw, loc))
  }

  // X select_first(Array[X?])
  //
  // return the first none null element. Throw an exception if nothing is found
  //
  // since: draft-2
  protected def select_first(args: Vector[V], loc: SourceLocation): V = {
    assert(args.size == 1)
    val vec = getWdlVector(args(0), loc)
    val values = vec.flatMap {
      case V_Null        => None
      case V_Optional(x) => Some(x)
      case x             => Some(x)
    }
    if (values.isEmpty)
      throw new EvalException("select_first: found no non-null elements", loc)
    values.head
  }

  // Array[X] select_all(Array[X?])
  //
  // since: draft-2
  protected def select_all(args: Vector[V], loc: SourceLocation): V = {
    assert(args.size == 1)
    val vec = getWdlVector(args(0), loc)
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
  protected def defined(args: Vector[V], loc: SourceLocation): V = {
    assert(args.size == 1)
    args.head match {
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
  protected def basename(args: Vector[V], loc: SourceLocation): V_String = {
    args.size match {
      case 1 =>
        val s = basenameCore(args.head, loc)
        V_String(s)
      case 2 =>
        val s = basenameCore(args(0), loc)
        val suff = getWdlString(args(1), loc)
        V_String(s.stripSuffix(suff))
      case _ =>
        throw new EvalException(s"basename: wrong number of arguments", loc)
    }
  }

  protected def floor(args: Vector[V], loc: SourceLocation): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, loc)
    V_Int(Math.floor(x).toInt)
  }

  protected def ceil(args: Vector[V], loc: SourceLocation): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, loc)
    V_Int(Math.ceil(x).toInt)
  }

  protected def round(args: Vector[V], loc: SourceLocation): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, loc)
    V_Int(Math.round(x).toInt)
  }

  protected def glob(args: Vector[V], loc: SourceLocation): V_Array = {
    assert(args.size == 1)
    val pattern = getWdlString(args.head, loc)
    val filenames = iosp.glob(pattern)
    V_Array(filenames.map { filepath =>
      V_File(filepath)
    })
  }

  protected def sep(args: Vector[V], loc: SourceLocation): V_String = {
    val separator = getWdlString(args(0), loc)
    val strings = getWdlVector(args(1), loc).map(x => getWdlString(x, loc))
    V_String(strings.mkString(separator))
  }
}
