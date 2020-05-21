package wdlTools.eval

import java.net.URL
import java.nio.file.{Path, Paths}
import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.syntax.TextSource
import wdlTools.util.Options
import wdlTools.types.WdlTypes._

case class StdlibV1(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL])
    extends StandardLibraryImpl {
  private val iosp = IoSupp(opts, evalCfg)
  private val coercion = Coercion(docSourceUrl)

  type FunctionImpl = (Vector[WdlValues.V], TextSource) => V

  private val funcTable: Map[String, FunctionImpl] = Map(
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

  private def getWdlFile(args: Vector[V], text: TextSource): V_File = {
    // process arguments
    assert(args.size == 1)
    coercion.coerceTo(T_File, args.head, text).asInstanceOf[V_File]
  }

  private def getWdlInt(arg: V, text: TextSource): Int = {
    val n = coercion.coerceTo(T_Int, arg, text).asInstanceOf[V_Int]
    n.value
  }

  private def getWdlFloat(arg: V, text: TextSource): Double = {
    val x = coercion.coerceTo(T_Float, arg, text).asInstanceOf[V_Float]
    x.value
  }

  private def getWdlString(arg: V, text: TextSource): String = {
    val s = coercion.coerceTo(T_String, arg, text).asInstanceOf[V_String]
    s.value
  }

  private def getWdlVector(value: V, text: TextSource): Vector[V] = {
    value match {
      case V_Array(ar) => ar
      case other       => throw new EvalException(s"${other} should be an array", text, docSourceUrl)
    }
  }

  private def stdout(args: Vector[V], text: TextSource): V_File = {
    assert(args.isEmpty)
    V_File(evalCfg.stdout.toString)
  }

  private def stderr(args: Vector[V], text: TextSource): V_File = {
    assert(args.isEmpty)
    V_File(evalCfg.stdout.toString)
  }

  // Array[String] read_lines(String|File)
  //
  private def read_lines(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    V_Array(lines.map(x => V_String(x)).toVector)
  }

  // Array[Array[String]] read_tsv(String|File)
  //
  private def read_tsv(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    val lines: Vector[String] = content.split("\n").toVector
    V_Array(lines.map { x =>
      val words = x.split("\t").toVector
      V_Array(words.map(V_String))
    })
  }

  // Map[String, String] read_map(String|File)
  //
  private def read_map(args: Vector[V], text: TextSource): V_Map = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    V_Map(lines.map { x =>
      val words = x.trim.split("\t")
      if (words.length != 2)
        throw new EvalException(s"read_tsv ${file}, line has ${words.length} words",
                                text,
                                docSourceUrl)
      V_String(words(0)) -> V_String(words(1))
    }.toMap)
  }

  // Object read_object(String|File)
  private def read_object(args: Vector[V], text: TextSource): V_Object = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    if (lines.size != 2)
      throw new EvalException(s"read_object : file ${file.toString} must contain two lines",
                              text,
                              docSourceUrl)
    val keys = lines(0).split("\t")
    val values = lines(1).split("\t")
    if (keys.size != values.size)
      throw new EvalException(
          s"read_object : the number of keys (${keys.size}) must be the same as the number of values (${values.size})",
          text,
          docSourceUrl
      )

    // Note all the values are going to be strings here. This probably isn't what
    // the user wants.
    val m = (keys zip values).map {
      case (k, v) =>
        k -> V_String(v)
    }.toMap
    V_Object(m)
  }

  // Array[Object] read_objects(String|File)
  private def read_objects(args: Vector[V], text: TextSource): V_Array = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    if (lines.size < 2)
      throw new EvalException(s"read_object : file ${file.toString} must contain at least two",
                              text,
                              docSourceUrl)
    val keys = lines.head.split("\t")
    val objects = lines.tail.map { line =>
      val values = line.split("\t")
      if (keys.size != values.size)
        throw new EvalException(
            s"read_object : the number of keys (${keys.size}) must be the same as the number of values (${values.size})",
            text,
            docSourceUrl
        )
      // Note all the values are going to be strings here. This probably isn't what
      // the user wants.
      val m = (keys zip values).map {
        case (k, v) =>
          k -> V_String(v)
      }.toMap
      V_Object(m)
    }.toVector
    V_Array(objects)
  }

  // mixed read_json(String|File)
  private def read_json(args: Vector[V], text: TextSource): V = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    try {
      Serialize.fromJson(content.parseJson)
    } catch {
      case e: JsonSerializationException =>
        throw new EvalException(e.getMessage, text, docSourceUrl)
    }
  }

  // Int read_int(String|File)
  //
  private def read_int(args: Vector[V], text: TextSource): V_Int = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    try {
      V_Int(content.trim.toInt)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to an integer", text, docSourceUrl)
    }
  }

  // String read_string(String|File)
  //
  private def read_string(args: Vector[V], text: TextSource): V_String = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    V_String(content)
  }

  // Float read_float(String|File)
  //
  private def read_float(args: Vector[V], text: TextSource): V_Float = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    try {
      V_Float(content.trim.toDouble)
    } catch {
      case _: Throwable =>
        throw new EvalException(s"could not convert (${content}) to a float", text, docSourceUrl)
    }
  }

  // Boolean read_boolean(String|File)
  //
  private def read_boolean(args: Vector[V], text: TextSource): V_Boolean = {
    val file = getWdlFile(args, text)
    val content = iosp.readFile(file.value)
    content.trim.toLowerCase() match {
      case "false" => V_Boolean(false)
      case "true"  => V_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert (${content}) to a boolean", text, docSourceUrl)
    }
  }

  // File write_lines(Array[String])
  //
  private def write_lines(args: Vector[V], text: TextSource): V_File = {
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
      .mkString("\n")
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, strRepr)
    V_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  private def write_tsv(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val arAr: V_Array =
      coercion.coerceTo(T_Array(T_Array(T_String)), args.head, text).asInstanceOf[V_Array]
    val tblRepr: String = arAr.value
      .map {
        case V_Array(a) =>
          a.map {
              case V_String(s) => s
              case other =>
                throw new EvalException(s"${other} should be a string", text, docSourceUrl)
            }
            .mkString("\t")
        case other =>
          throw new EvalException(s"${other} should be an array", text, docSourceUrl)
      }
      .mkString("\n")
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, tblRepr)
    V_File(tmpFile.toString)
  }

  // File write_map(Map[String, String])
  //
  private def write_map(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val m: V_Map =
      coercion.coerceTo(T_Map(T_String, T_String), args.head, text).asInstanceOf[V_Map]
    val mapStrRepr = m.value
      .map {
        case (V_String(key), V_String(value)) => key + "\t" + value
        case (k, v) =>
          throw new EvalException(s"${k} ${v} should both be strings", text, docSourceUrl)
      }
      .mkString("\n")
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, mapStrRepr)
    V_File(tmpFile.toString)
  }

  private def lineFromObject(obj: V_Object, text: TextSource): String = {
    obj.members.values
      .map { vw =>
        coercion.coerceTo(T_String, vw, text).asInstanceOf[V_String].value
      }
      .mkString("\t")
  }

  // File write_object(Object)
  //
  private def write_object(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val obj = coercion.coerceTo(T_Object, args.head, text).asInstanceOf[V_Object]
    val keyLine = obj.members.keys.mkString("\t")
    val valueLine = lineFromObject(obj, text)
    val content = keyLine + "\n" + valueLine
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, content)
    V_File(tmpFile.toString)
  }

  // File write_objects(Array[Object])
  //
  private def write_objects(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val objs = coercion.coerceTo(T_Array(T_Object), args.head, text).asInstanceOf[V_Array]
    val objArray = objs.value.asInstanceOf[Vector[V_Object]]
    if (objArray.isEmpty)
      throw new EvalException("write_objects: empty input array", text, docSourceUrl)

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

    val keyLine = fstObj.members.keys.mkString("\t")
    val valueLines = objArray
      .map { obj =>
        lineFromObject(obj, text)
      }
      .mkString("\n")
    val content = keyLine + "\n" + valueLines
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, content)
    V_File(tmpFile.toString)
  }

  private def write_json(args: Vector[V], text: TextSource): V_File = {
    assert(args.size == 1)
    val jsv =
      try {
        Serialize.toJson(args.head)
      } catch {
        case e: JsonSerializationException =>
          throw new EvalException(e.getMessage, text, docSourceUrl)
      }
    val tmpFile: Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, jsv.prettyPrint)
    V_File(tmpFile.toString)
  }

  // Our size implementation is a little bit more general than strictly necessary.
  //
  // recursively dive into the entire structure and sum up all the file sizes. Strings
  // are coerced into file paths.
  private def sizeCore(arg: V, text: TextSource): Double = {
    def f(arg: V): Double = {
      arg match {
        case V_String(path) => iosp.size(path).toDouble
        case V_File(path)   => iosp.size(path).toDouble
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
  private def size(args: Vector[V], text: TextSource): V_Float = {
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
  private def sub(args: Vector[V], text: TextSource): V_String = {
    assert(args.size == 3)
    val input = getWdlString(args(0), text)
    val pattern = getWdlString(args(1), text)
    val replace = getWdlString(args(2), text)
    V_String(input.replaceAll(pattern, replace))
  }

  // Array[Int] range(Int)
  private def range(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val n = getWdlInt(args.head, text)
    val vec: Vector[V] = Vector.tabulate(n)(i => V_Int(i))
    V_Array(vec)
  }

  // Array[Array[X]] transpose(Array[Array[X]])
  //
  private def transpose(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val vec: Vector[V] = getWdlVector(args.head, text)
    val vec_vec: Vector[Vector[V]] = vec.map(v => getWdlVector(v, text))
    val trValue = vec_vec.transpose
    V_Array(trValue.map(vec => V_Array(vec)))
  }

  // Array[Pair(X,Y)] zip(Array[X], Array[Y])
  //
  private def zip(args: Vector[V], text: TextSource): V_Array = {
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
  private def cross(args: Vector[V], text: TextSource): V_Array = {
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

  // Integer length(Array[X])
  //
  private def length(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val vec = getWdlVector(args.head, text)
    V_Int(vec.size)
  }

  // Array[X] flatten(Array[Array[X]])
  //
  private def flatten(args: Vector[V], text: TextSource): V_Array = {
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
  private def prefix(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 2)
    val pref = getWdlString(args(0), text)
    val vec = getWdlVector(args(1), text)
    V_Array(vec.map { vw =>
      val str = Serialize.primitiveValueToString(vw, text, docSourceUrl)
      V_String(pref + str)
    })
  }

  // X select_first(Array[X?])
  //
  // return the first none null element. Throw an exception if nothing is found
  private def select_first(args: Vector[V], text: TextSource): V = {
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

  private def select_all(args: Vector[V], text: TextSource): V = {
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
  private def defined(args: Vector[V], text: TextSource): V = {
    assert(args.size == 1)
    args.head match {
      case V_Null => V_Boolean(false)
      case _      => V_Boolean(true)
    }
  }

  def basenameCore(arg: V, text: TextSource): String = {
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
  private def basename(args: Vector[V], text: TextSource): V_String = {
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

  private def floor(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.floor(x).toInt)
  }

  private def ceil(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.ceil(x).toInt)
  }

  private def round(args: Vector[V], text: TextSource): V_Int = {
    assert(args.size == 1)
    val x = getWdlFloat(args.head, text)
    V_Int(Math.round(x).toInt)
  }

  private def glob(args: Vector[V], text: TextSource): V_Array = {
    assert(args.size == 1)
    val pattern = getWdlString(args.head, text)
    val filenames = iosp.glob(pattern)
    V_Array(filenames.map { filepath =>
      V_File(filepath)
    })
  }

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
}
