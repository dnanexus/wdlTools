package wdlTools.eval.stdlib

import java.net.URL
import java.nio.file.{Path, Paths}

import wdlTools.eval.WdlValues._
import wdlTools.eval._
import wdlTools.syntax.TextSource
import wdlTools.types.WdlTypes._
import wdlTools.util.Options

import kantan.csv._
import kantan.csv.CsvConfiguration.{Header, QuotePolicy}
import kantan.csv.ops._
import spray.json._

abstract class BaseStdlib(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL])
    extends StandardLibraryImpl {
  type FunctionImpl = (Vector[WdlValues.V], TextSource) => V

  protected val coercion: Coercion = Coercion(docSourceUrl)
  protected val iosp: IoSupp = stdlib.IoSupp(opts, evalCfg, docSourceUrl)

  protected def funcTable: Map[String, FunctionImpl]

  def call(funcName: String, args: Vector[V], text: TextSource): V = {
    if (!(funcTable contains funcName))
      throw new EvalException(s"stdlib function ${funcName} not implemented", text, docSourceUrl)
    val impl = funcTable(funcName)
    try {
      impl(args, text)
    } catch {
      case e: EvalException =>
        throw e
      case e: Throwable =>
        val msg = s"""|calling stdlib function ${funcName} with arguments ${args}
                      |${e.getMessage}
                      |""".stripMargin
        throw new EvalException(msg, text, docSourceUrl)
    }
  }

  protected def getWdlFile(args: Vector[V], text: TextSource): V_File = {
    // process arguments
    assert(args.size == 1)
    coercion.coerceTo(T_File, args.head, text).asInstanceOf[V_File]
  }

  protected def getWdlInt(arg: V, text: TextSource): Int = {
    val n = coercion.coerceTo(T_Int, arg, text).asInstanceOf[V_Int]
    n.value
  }

  protected def getWdlFloat(arg: V, text: TextSource): Double = {
    val x = coercion.coerceTo(T_Float, arg, text).asInstanceOf[V_Float]
    x.value
  }

  protected def getWdlString(arg: V, text: TextSource): String = {
    val s = coercion.coerceTo(T_String, arg, text).asInstanceOf[V_String]
    s.value
  }

  protected def getWdlVector(value: V, text: TextSource): Vector[V] = {
    value match {
      case V_Array(ar) => ar
      case other       => throw new EvalException(s"${other} should be an array", text, docSourceUrl)
    }
  }

  protected def getWdlMap(value: V, text: TextSource): Map[V, V] = {
    value match {
      case V_Map(m) => m
      case other    => throw new EvalException(s"${other} should be a map", text, docSourceUrl)
    }
  }

  protected def getWdlPair(value: V, text: TextSource): (V, V) = {
    value match {
      case V_Pair(l, r) => (l, r)
      case other        => throw new EvalException(s"${other} should be a pair", text, docSourceUrl)
    }
  }

  // since: draft-1
  protected def stdout(args: Vector[V], text: TextSource): V_File = {
    assert(args.isEmpty)
    V_File(evalCfg.stdout.toString)
  }

  // since: draft-1
  protected def stderr(args: Vector[V], text: TextSource): V_File = {
    assert(args.isEmpty)
    V_File(evalCfg.stdout.toString)
  }

  // Array[String] read_lines(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_lines(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    V_Array(content.getLines.map(x => V_String(x)).toVector)
  }

  private val tsvConf = CsvConfiguration('\t', '"', QuotePolicy.WhenNeeded, Header.None)

  // Array[Array[String]] read_tsv(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_tsv(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    val reader = content.mkString.asCsvReader[Vector[String]](tsvConf)
    V_Array(reader.map {
      case Left(err) =>
        throw new EvalException(s"Invalid tsv file ${file}: ${err}", text, docSourceUrl)
      case Right(row) => V_Array(row.map(x => V_String(x)))
    }.toVector)
  }

  // Map[String, String] read_map(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_map(args: Vector[V], text: TextSource): V_Map = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    val reader = content.mkString.asCsvReader[(String, String)](tsvConf)
    V_Map(
        reader
          .map {
            case Left(err) =>
              throw new EvalException(s"Invalid tsv file ${file}: ${err}", text, docSourceUrl)
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
  protected def read_object(args: Vector[V], text: TextSource): V_Object = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text).mkString
    val lines: Vector[Vector[String]] = content
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    lines match {
      case Vector(keys, values) => kvToObject(keys, values, text)
      case _ =>
        throw new EvalException(
            s"read_object : file ${file.toString} must contain exactly two lines",
            text,
            docSourceUrl
        )
    }
  }

  // Array[Object] read_objects(String|File)
  //
  // since: draft-1
  // deprecation:
  // * beginning in draft-2, URI parameter is not supported
  // * removed in Version 2
  protected def read_objects(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    val lines = content.mkString
      .asCsvReader[Vector[String]](tsvConf)
      .map {
        case Left(err)  => throw new EvalException(s"Invalid tsv file ${file}: ${err}")
        case Right(row) => row
      }
      .toVector
    if (lines.size < 2) {
      throw new EvalException(s"read_object : file ${file.toString} must contain at least two",
                              text,
                              docSourceUrl)
    }
    val keys = lines.head
    V_Array(lines.tail.map(values => kvToObject(keys, values, text)))
  }

  private def kvToObject(keys: Vector[String],
                         values: Vector[String],
                         text: TextSource): V_Object = {
    if (keys.size != values.size) {
      throw new EvalException(
          s"""read_object : the number of keys (${keys.size}) 
             |must be the same as the number of values (${values.size})""".stripMargin
            .replace("\t", " "),
          text,
          docSourceUrl
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
  protected def read_json(args: Vector[V], text: TextSource): V = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    try {
      Serialize.fromJson(content.mkString.parseJson)
    } catch {
      case e: JsonSerializationException =>
        throw new EvalException(e.getMessage, text, docSourceUrl)
    }
  }

  // Int read_int(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_int(args: Vector[V], text: TextSource): V_Int = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    try {
      V_Int(content.mkString.trim.toInt)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to an integer", text, docSourceUrl)
    }
  }

  // String read_string(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_string(args: Vector[V], text: TextSource): V_String = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    V_String(content.mkString)
  }

  // Float read_float(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_float(args: Vector[V], text: TextSource): V_Float = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    try {
      V_Float(content.mkString.trim.toDouble)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to a float", text, docSourceUrl)
    }
  }

  // Boolean read_boolean(String|File)
  //
  // since: draft-1
  // deprecation: beginning in draft-2, URI parameter is not supported
  protected def read_boolean(args: Vector[V], text: TextSource): V_Boolean = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value, text)
    content.mkString.trim.toLowerCase() match {
      case "false" => V_Boolean(false)
      case "true"  => V_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert (${content}) to a boolean", text, docSourceUrl)
    }
  }

  // File write_lines(Array[String])
  //
  // since: draft-1
  protected def write_lines(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val array: V_Array =
      coercion.coerceTo(T_Array(T_String), args.head, text).asInstanceOf[V_Array]
    val strRepr: String = array.value
      .map {
        case V_String(x) => x
        case other =>
          throw new EvalException(s"write_lines: element ${other} should be a string",
                                  text,
                                  docSourceUrl)
      }
      // note: '\n' line endings explicitly specified in the spec
      .mkString("\n")
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, strRepr, text)
    V_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  // since: draft-1
  protected def write_tsv(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val arAr: V_Array =
      coercion.coerceTo(T_Array(T_Array(T_String)), args.head, text).asInstanceOf[V_Array]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      arAr.value
        .foreach {
          case V_Array(a) =>
            val row = a.map {
              case V_String(s) => s
              case other =>
                throw new EvalException(s"${other} should be a string", text, docSourceUrl)
            }
            writer.write(row)
          case other =>
            throw new EvalException(s"${other} should be an array", text, docSourceUrl)
        }
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_map(Map[String, String])
  //
  // since: draft-1
  protected def write_map(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val m: V_Map =
      coercion.coerceTo(T_Map(T_String, T_String), args.head, text).asInstanceOf[V_Map]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[(String, String)](tsvConf)
    try {
      m.value
        .foreach {
          case (V_String(key), V_String(value)) => writer.write((key, value))
          case (k, v) =>
            throw new EvalException(s"${k} ${v} should both be strings", text, docSourceUrl)
        }
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  private def lineFromObject(obj: V_Object, text: TextSource): Vector[String] = {
    obj.members.values.map { vw =>
      coercion.coerceTo(T_String, vw, text).asInstanceOf[V_String].value
    }.toVector
  }

  // File write_object(Object)
  //
  // since: draft-1
  // deprecation: removed in Version 2
  protected def write_object(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val obj = coercion.coerceTo(T_Object, args.head, text).asInstanceOf[V_Object]
    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(obj.members.keys.toVector)
      writer.write(lineFromObject(obj, text))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_objects(Array[Object])
  //
  // since: draft-1
  // deprecation: removed in Version 2
  protected def write_objects(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val objs = coercion.coerceTo(T_Array(T_Object), args.head, text).asInstanceOf[V_Array]
    val objArray = objs.value.asInstanceOf[Vector[V_Object]]
    if (objArray.isEmpty) {
      throw new EvalException("write_objects: empty input array", text, docSourceUrl)
    }

    // check that all objects have the same keys
    val fstObj = objArray(0)
    val keys = fstObj.members.keys.toSet
    objArray.tail.foreach { obj =>
      if (obj.members.keys.toSet != keys)
        throw new EvalException(
            "write_objects: the keys are not the same for all objects in the array",
            text,
            docSourceUrl
        )
    }

    val tmpFile: Path = iosp.mkTempFile()
    val writer = tmpFile.asCsvWriter[Vector[String]](tsvConf)
    try {
      writer.write(fstObj.members.keys.toVector)
      objArray.foreach(obj => writer.write(lineFromObject(obj, text)))
    } finally {
      writer.close()
    }
    V_File(tmpFile.toString)
  }

  // File write_json(mixed)
  //
  // since: draft-1
  protected def write_json(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val jsv =
      try {
        Serialize.toJson(args.head)
      } catch {
        case e: JsonSerializationException =>
          throw new EvalException(e.getMessage, text, docSourceUrl)
      }
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, jsv.prettyPrint, text)
    V_File(tmpFile.toString)
  }

  // Our size implementation is a little bit more general than strictly necessary.
  //
  // recursively dive into the entire structure and sum up all the file sizes. Strings
  // are coerced into file paths.
  private def sizeCore(arg: V, text: TextSource): Double = {
    def f(arg: V): Double = {
      arg match {
        case V_String(path) => iosp.size(path, text).toDouble
        case V_File(path)   => iosp.size(path, text).toDouble
        case V_Array(ar) =>
          ar.foldLeft(0.0) {
            case (accu, wv) => accu + f(wv)
          }
        case V_Optional(value) => f(value)
        case _                 => 0
      }
    }
    try {
      f(arg)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"size(${arg})", text, docSourceUrl)
    }
  }

  // sUnit is a units parameter (KB, KiB, MB, GiB, ...)
  private def sizeUnit(sUnit: String, text: TextSource): Double = {
    sUnit.toLowerCase match {
      case "b"   => 1
      case "kb"  => 1000d
      case "mb"  => 1000d * 1000d
      case "gb"  => 1000d * 1000d * 1000d
      case "tb"  => 1000d * 1000d * 1000d * 1000d
      case "kib" => 1024d
      case "mib" => 1024d * 1024d
      case "gib" => 1024d * 1024d * 1024d
      case "tib" => 1024d * 1024d * 1024d * 1024d
      case _     => throw new EvalException(s"Unknown unit ${sUnit}", text, docSourceUrl)
    }
  }

  // Size can take several kinds of arguments.
  //
  // since: draft-2
  // version differences: in 1.0, the spec was updated to explicitly support compount argument types;
  //  however, our implementation supports compound types for all versions, and goes further than the
  //  spec requires to support nested collections.
  protected def size(args: Vector[V], text: TextSource): V_Float = {
    args.size match {
      case 1 =>
        V_Float(sizeCore(args.head, text))
      case 2 =>
        val sUnit = getWdlString(args(1), text)
        val nBytesInUnit = sizeUnit(sUnit, text)
        val nBytes = sizeCore(args.head, text)
        V_Float(nBytes / nBytesInUnit)
      case _ =>
        throw new EvalException("size: called with wrong number of arguments", text, docSourceUrl)
    }
  }

  // String sub(String, String, String)
  //
  // Given 3 String parameters input, pattern, replace, this function
  // will replace any occurrence matching pattern in input by
  // replace. pattern is expected to be a regular expression.
  //
  // since: draft-2
  protected def sub(args: Vector[V], text: TextSource): V_String = {
    assert(args.size == 3)
    val input = getWdlString(args(0), text)
    val pattern = getWdlString(args(1), text)
    val replace = getWdlString(args(2), text)
    V_String(input.replaceAll(pattern, replace))
  }

  // Array[Int] range(Int)
  //
  // since: draft-2
  protected def range(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val n = getWdlInt(args.head, text)
    val vec: Vector[V] = Vector.tabulate(n)(i => V_Int(i))
    V_Array(vec)
  }

  // Array[Array[X]] transpose(Array[Array[X]])
  //
  // since: draft-2
  protected def transpose(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val vec: Vector[V] = getWdlVector(args.head, text)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, text))
    val trValue = vec_vec.transpose
    V_Array(trValue.map(vec => V_Array(vec)))
  }

  // Array[Pair(X,Y)] zip(Array[X], Array[Y])
  //
  // since: draft-2
  protected def zip(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 2)
    val ax = getWdlVector(args(0), text)
    val ay = getWdlVector(args(1), text)

    V_Array((ax zip ay).map {
      case (x, y) => V_Pair(x, y)
    })
  }

  // Array[Pair(X,Y)] cross(Array[X], Array[Y])
  //
  // cartesian product of two arrays. Results in an n x m sized array of pairs.
  //
  // since: draft-2
  protected def cross(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 2)
    val ax = getWdlVector(args(0), text)
    val ay = getWdlVector(args(1), text)

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
  protected def as_pairs(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val map = getWdlMap(args.head, text)
    V_Array(map.map {
      case (key, value) => V_Pair(key, value)
    }.toVector)
  }

  // Map[X,Y] as_map(Array[Pair[X,Y]])
  //
  // since: V2
  protected def as_map(args: Vector[V], text: TextSource): V_Map = {
    assert(args.size == 1)
    val vec = getWdlVector(args.head, text)
    V_Map(vec.map(item => getWdlPair(item, text)).toMap)
  }

  // Array[X] keys(Map[X,Y])
  //
  // since: V2
  protected def keys(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val map = getWdlMap(args.head, text)
    V_Array(map.keys.toVector)
  }

  // Map[X,Array[Y]] collect_by_key(Array[Pair[X,Y]])
  //
  // since: V2
  protected def collect_by_key(args: Vector[V], text: TextSource): V_Map = {
    assert(args.size == 1)
    val vec: Vector[(V, V)] = getWdlVector(args.head, text).map(item => getWdlPair(item, text))
    V_Map(
        vec.groupBy(_._1).map {
          case (key, values: Vector[(V, V)]) => key -> V_Array(values.map(_._2))
        }
    )
  }

  // Integer length(Array[X])
  //
  // since: draft-2
  protected def length(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val vec = getWdlVector(args.head, text)
    V_Int(vec.size)
  }

  // Array[X] flatten(Array[Array[X]])
  //
  // since: draft-2
  protected def flatten(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val vec: Vector[V] = getWdlVector(args.head, text)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, text))
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
  protected def prefix(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 2)
    val pref = getWdlString(args(0), text)
    val vec = getStringVector(args(1), text)
    V_Array(vec.map(str => V_String(pref + str)))
  }

  // Array[String] prefix(String, Array[X])
  //
  // Given a String and an Array[X] where X is a primitive type, the
  // suffix function returns an array of strings comprised of each
  // element of the input array suffixed by the specified suffix
  // string.
  //
  // since: V2
  protected def suffix(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 2)
    val suff = getWdlString(args(0), text)
    val vec = getStringVector(args(1), text)
    V_Array(vec.map(str => V_String(str + suff)))
  }

  // Array[String] quote(Array[X])
  //
  // since: V2
  protected def quote(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val vec = getStringVector(args(1), text)
    val dquote = '"'
    V_Array(vec.map(str => V_String(s"${dquote}${str}${dquote}")))
  }

  // Array[String] squote(Array[X])
  //
  // since: V2
  protected def squote(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val vec = getStringVector(args(1), text)
    V_Array(vec.map(str => V_String(s"'${str}'")))
  }

  private def getStringVector(arg: V, text: TextSource): Vector[String] = {
    getWdlVector(arg, text).map(vw => Serialize.primitiveValueToString(vw, text, docSourceUrl))
  }

  // X select_first(Array[X?])
  //
  // return the first none null element. Throw an exception if nothing is found
  //
  // since: draft-2
  protected def select_first(args: Vector[V], text: TextSource): V = {
    assert(args.size == 1)
    val vec = getWdlVector(args(0), text)
    val values = vec.flatMap {
      case V_Null        => None
      case V_Optional(x) => Some(x)
      case x             => Some(x)
    }
    if (values.isEmpty)
      throw new EvalException("select_first: found no non-null elements", text, docSourceUrl)
    values.head
  }

  // Array[X] select_all(Array[X?])
  //
  // since: draft-2
  protected def select_all(args: Vector[V], text: TextSource): V = {
    assert(args.size == 1)
    val vec = getWdlVector(args(0), text)
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
  protected def defined(args: Vector[V], text: TextSource): V = {
    assert(args.size == 1)
    args.head match {
      case V_Null => V_Boolean(false)
      case _      => V_Boolean(true)
    }
  }

  private def basenameCore(arg: V, text: TextSource): String = {
    val filePath = arg match {
      case V_File(s)   => s
      case V_String(s) => s
      case other =>
        throw new EvalException(s"${other} must be a string or a file type", text, docSourceUrl)
    }
    Paths.get(filePath).getFileName.toString
  }

  // String basename(String)
  //
  // This function returns the basename of a file path passed to it: basename("/path/to/file.txt") returns "file.txt".
  // Also supports an optional parameter, suffix to remove: basename("/path/to/file.txt", ".txt") returns "file".
  //
  protected def basename(args: Vector[V], text: TextSource): V_String = {
    args.size match {
      case 1 =>
        val s = basenameCore(args.head, text)
        V_String(s)
      case 2 =>
        val s = basenameCore(args(0), text)
        val suff = getWdlString(args(1), text)
        V_String(s.stripSuffix(suff))
      case _ =>
        throw new EvalException(s"basename: wrong number of arguments", text, docSourceUrl)
    }
  }

  protected def floor(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.floor(x).toInt)
  }

  protected def ceil(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.ceil(x).toInt)
  }

  protected def round(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.round(x).toInt)
  }

  protected def glob(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val pattern = getWdlString(args.head, text)
    val filenames = iosp.glob(pattern)
    V_Array(filenames.map { filepath =>
      V_File(filepath)
    })
  }
}
