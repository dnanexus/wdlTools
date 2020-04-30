package wdlTools.eval

import java.net.URL
import java.nio.file.Path
import wdlTools.eval.WdlValues._
import wdlTools.syntax.TextSource
import wdlTools.util.{EvalConfig, Options}
import wdlTools.typing.WdlTypes._

case class StdlibV1(opts: Options, evalCfg: EvalConfig, docSourceURL: Option[URL])
    extends StandardLibraryImpl {
  private val iosp = IoSupp(opts, evalCfg)
  private val coercion = Coercion(docSourceURL)

  type FunctionImpl = (Vector[WdlValues.WV], TextSource) => WV

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
/*    "sub" -> sub,
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
    "basename" -> basename, */
      "floor" -> floor,
      "ceil" -> ceil,
      "round" -> round
//    "glob" -> glob
  )

  private def getFile(args: Vector[WV], text: TextSource): WV_File = {
    // process arguments
    assert(args.size == 1)
    coercion.coerceTo(WT_File, args.head, text).asInstanceOf[WV_File]
  }

  private def stdout(args: Vector[WV], text: TextSource): WV_File = {
    assert(args.size == 0)
    WV_File(evalCfg.stdout.toString)
  }

  private def stderr(args: Vector[WV], text: TextSource): WV_File = {
    assert(args.size == 0)
    WV_File(evalCfg.stdout.toString)
  }

  // Array[String] read_lines(String|File)
  //
  private def read_lines(args: Vector[WV], text: TextSource): WV_Array = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    WV_Array(lines.map(x => WV_String(x)).toVector)
  }

  // Array[Array[String]] read_tsv(String|File)
  //
  private def read_tsv(args: Vector[WV], text: TextSource): WV_Array = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    val lines: Vector[String] = content.split("\n").toVector
    WV_Array(lines.map { x =>
      val words = x.split("\t").toVector
      WV_Array(words.map(WV_String(_)))
    })
  }

  // Map[String, String] read_map(String|File)
  //
  private def read_map(args: Vector[WV], text: TextSource): WV_Map = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    WV_Map(lines.map { x =>
      val words = x.trim.split("\t")
      if (words.length != 2)
        throw new EvalException(s"read_tsv ${file}, line has ${words.length} words",
                                text,
                                docSourceURL)
      WV_String(words(0)) -> WV_String(words(1))
    }.toMap)
  }

  // Object read_object(String|File)
  private def read_object(args: Vector[WV], text: TextSource): WV_Object = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    val lines = content.split("\n")
    if (lines.size != 2)
      throw new EvalException(s"read_object : file ${file.toString} must contain two lines",
                              text, docSourceURL)
    val keys = lines(0).split("\t")
    val values = lines(1).split("\t")
    if (keys.size != values.size)
      throw new EvalException(
        s"read_object : the number of keys (${keys.size}) must be the same as the number of values (${values.size})",
        text, docSourceURL)

    // Note all the values are going to be strings here. This probably isn't what
    // the user wants.
    val m = (keys zip values).map{ case (k,v) =>
      k -> WV_String(v)
    }.toMap
    WV_Object(m)
  }

  // Array[Object] read_objects(String|File)
  private def read_objects(args: Vector[WV], text: TextSource): WV_Object =
    throw new EvalException("not implemented", text, docSourceURL)

  // mixed read_json(String|File)
  private def read_json(args: Vector[WV], text: TextSource): WV_Object =
    throw new EvalException("not implemented", text, docSourceURL)

  // Int read_int(String|File)
  //
  private def read_int(args: Vector[WV], text: TextSource): WV_Int = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    try {
      WV_Int(content.trim.toInt)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"could not convert (${content}) to an integer", text, docSourceURL)
    }
  }

  // String read_string(String|File)
  //
  private def read_string(args: Vector[WV], text: TextSource): WV_String = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    WV_String(content)
  }

  // Float read_float(String|File)
  //
  private def read_float(args: Vector[WV], text: TextSource): WV_Float = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    try {
      WV_Float(content.trim.toDouble)
    } catch {
      case e: Throwable =>
        throw new EvalException(s"could not convert (${content}) to a float", text, docSourceURL)
    }
  }

  // Boolean read_boolean(String|File)
  //
  private def read_boolean(args: Vector[WV], text: TextSource): WV_Boolean = {
    val file = getFile(args, text)
    val content = iosp.readFile(file.value)
    content.trim.toLowerCase() match {
      case "false" => WV_Boolean(false)
      case "true"  => WV_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert (${content}) to a boolean", text, docSourceURL)
    }
  }

  // File write_lines(Array[String])
  //
  private def write_lines(args : Vector[WV], text : TextSource) : WV_File = {
    assert(args.size == 1)
    val array : WV_Array = coercion.coerceTo(WT_Array(WT_String), args.head, text).asInstanceOf[WV_Array]
    val strRepr : String = array.value.map{
      case WV_String(x) => x
      case other => throw new EvalException(s"write_lines: element ${other} should be a string",
                                            text, docSourceURL)
    }.mkString("\n")
    val tmpFile : Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, strRepr)
    WV_File(tmpFile.toString)
  }

  // File write_tsv(Array[Array[String]])
  //
  private def write_tsv(args : Vector[WV], text : TextSource) : WV_File = {
    assert(args.size == 1)
    val arAr : WV_Array = coercion.coerceTo(WT_Array(WT_Array(WT_String)),
                                             args.head, text).asInstanceOf[WV_Array]
    val tblRepr : String = arAr.value.map{
      case WV_Array(a) =>
        a.map{
          case WV_String(s) => s
          case other => throw new EvalException(s"${other} should be a string", text, docSourceURL)
        }.mkString("\t")
      case other =>
        throw new EvalException(s"${other} should be an array", text, docSourceURL)
    }.mkString("\n")
    val tmpFile : Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, tblRepr)
    WV_File(tmpFile.toString)
  }

  // File write_map(Map[String, String])
  //
  private def write_map(args : Vector[WV], text : TextSource) : WV_File = {
    assert(args.size == 1)
    val m : WV_Map = coercion.coerceTo(WT_Map(WT_String, WT_String),
                                       args.head, text).asInstanceOf[WV_Map]
    val mapStrRepr = m.value.map{
      case (WV_String(key), WV_String(value)) => key + "\t" + value
      case (k, v) =>
        throw new EvalException(s"${k} ${v} should both be strings", text, docSourceURL)
    }.mkString("\n")
    val tmpFile : Path = iosp.mkTempFile()
    iosp.writeFile(tmpFile, mapStrRepr)
    WV_File(tmpFile.toString)
  }

  // File write_object(Object)
  //
  private def write_object(args : Vector[WV], text : TextSource) : WV_File = {
    throw new EvalException("write_object: not implemented", text, docSourceURL)
  }

  private def write_objects(args : Vector[WV], text : TextSource) : WV_File = {
    throw new EvalException("write_objects: not implemented", text, docSourceURL)
  }

  private def write_json(args : Vector[WV], text : TextSource) : WV_File = {
    throw new EvalException("write_json: not implemented", text, docSourceURL)
  }

  // Our size implementation is a little bit more general than strictly necessary.
  //
  // recursively dive into the entire structure and sum up all the file sizes. Strings
  // are coerced into file paths.
  private def sizeCore(arg : WV, text : TextSource) : Double = {
    def f(arg : WV) : Double = {
      arg match {
        case WV_String(path) => iosp.size(path)
        case WV_File(path) => iosp.size(path)
        case WV_Array(ar) =>
          ar.foldLeft(0.0) {
            case (accu, wv) => accu + f(wv)
          }
        case WV_Optional(value) => f(value)
        case _ => 0
      }
    }
    try {
      f(arg)
    } catch {
      case _ : Throwable =>
        throw new EvalException(s"size(${arg})", text, docSourceURL)
    }
  }

  // sUnit is a units parameter (KB, KiB, MB, GiB, ...)
  private def sizeUnit(sUnit : String, text : TextSource) : Double = {
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
      case _     => throw new EvalException(s"Unknown unit ${sUnit}", text, docSourceURL)
    }
  }

  // Size can take several kinds of arguments.
  private def size(args : Vector[WV], text : TextSource) : WV_Float = {
    args.size match {
      case 1 =>
        WV_Float(sizeCore(args.head, text))
      case 2 =>
        val sUnit = coercion.coerceTo(WT_String, args(1), text).asInstanceOf[WV_String]
        val nBytesInUnit = sizeUnit(sUnit.value, text)
        val nBytes = sizeCore(args.head, text)
        WV_Float(nBytes / nBytesInUnit)
      case _ =>
        throw new EvalException("size: called with wrong number of arguments", text, docSourceURL)
    }
  }

  /*
      WT_Function3("sub", WT_String, WT_String, WT_String, WT_String),
      WT_Function1("range", WT_Int, WT_Array(WT_Int)),
      // Array[Array[X]] transpose(Array[Array[X]])
      WT_Function1("transpose", WT_Array(WT_Array(WT_Var(0))), WT_Array(WT_Array(WT_Var(0)))),
      // Array[Pair(X,Y)] zip(Array[X], Array[Y])
      WT_Function2("zip",
                   WT_Array(WT_Var(0)),
                   WT_Array(WT_Var(1)),
                   WT_Array(WT_Pair(WT_Var(0), WT_Var(1)))),
      // Array[Pair(X,Y)] cross(Array[X], Array[Y])
      WT_Function2("cross",
                   WT_Array(WT_Var(0)),
                   WT_Array(WT_Var(1)),
                   WT_Array(WT_Pair(WT_Var(0), WT_Var(1)))),
      // Integer length(Array[X])
      WT_Function1("length", WT_Array(WT_Var(0)), WT_Int),
      // Array[X] flatten(Array[Array[X]])
      WT_Function1("flatten", WT_Array(WT_Array(WT_Var(0))), WT_Array(WT_Var(0))),
      WT_Function2("prefix", WT_String, WT_Array(WT_Var(0)), WT_String),
      WT_Function1("select_first", WT_Array(WT_Optional(WT_Var(0))), WT_Var(0)),
      WT_Function1("select_all", WT_Array(WT_Optional(WT_Var(0))), WT_Array(WT_Var(0))),
      WT_Function1("defined", WT_Optional(WT_Var(0)), WT_Boolean),
      // simple functions again
      WT_Function1("basename", WT_String, WT_String),
   */

  private def floor(args: Vector[WV], text: TextSource): WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.floor(x.value).toInt)
  }

  private def ceil(args: Vector[WV], text: TextSource): WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.ceil(x.value).toInt)
  }

  private def round(args: Vector[WV], text: TextSource): WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.round(x.value).toInt)
  }

  // not mentioned in the specification
  //WT_Function1("glob", WT_String, WT_Array(WT_File))

  def call(funcName: String, args: Vector[WV], text: TextSource): WV = {
    if (!(funcTable contains funcName))
      throw new EvalException(s"stdlib function ${funcName} not implemented", text, docSourceURL)
    val impl = funcTable(funcName)
    try {
      impl(args, text)
    } catch {
      case e: Throwable =>
        val msg = s"""|calling stdlib function ${funcName} with arguments ${args}
                      |${e.getMessage}
                      |""".stripMargin
        throw new EvalException(msg, text, docSourceURL)
    }
  }
}
