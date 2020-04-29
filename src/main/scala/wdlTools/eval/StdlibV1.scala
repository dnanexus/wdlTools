package wdlTools.eval

import java.net.URL
import wdlTools.eval.WdlValues._
import wdlTools.syntax.TextSource
import wdlTools.util.{EvalConfig, Options}
import wdlTools.typing.WdlTypes._

case class StdlibV1(opts : Options,
                    evalCfg: EvalConfig,
                    docSourceURL : Option[URL]) extends StandardLibraryImpl {
  private val ioFunctions = IoFunctions(opts, evalCfg)
  private val coercion = Coercion(docSourceURL)

  type FunctionImpl = (Vector[WdlValues.WV], TextSource) => WV

  private val funcTable : Map[String, FunctionImpl] = Map(
    "stdout" -> stdout,
    "stderr" -> stderr,
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
/*    "write_lines" -> write_lines,
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
    "basename" -> basename, */
    "floor" -> floor,
    "ceil" -> ceil,
    "round" -> round
//    "glob" -> glob
  )

  private def getFile(args : Vector[WV], text : TextSource) : WV_File = {
    // process arguments
    assert(args.size == 1)
    coercion.coerceTo(WT_File, args.head, text).asInstanceOf[WV_File]
  }

  private def stdout(args : Vector[WV], text : TextSource) : WV_File = {
    assert(args.size == 0)
    WV_File(evalCfg.stdout.toString)
  }

  private def stderr(args : Vector[WV], text : TextSource) : WV_File = {
    assert(args.size == 0)
    WV_File(evalCfg.stdout.toString)
  }

  // Array[String] read_lines(String|File)
  //
  private def read_lines(args : Vector[WV], text : TextSource) : WV_Array = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    val lines = content.split("\n")
    WV_Array(lines.map(x => WV_String(x)).toVector)
  }

  // Array[Array[String]] read_tsv(String|File)
  //
  private def read_tsv(args : Vector[WV], text : TextSource) : WV_Array = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    val lines : Vector[String] = content.split("\n").toVector
    WV_Array(lines.map{ x =>
               val words = x.split("\t").toVector
               WV_Array(words.map(WV_String(_)))
             })
  }

  // Map[String, String] read_map(String|File)
  //
  private def read_map(args : Vector[WV], text : TextSource) : WV_Map = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    val lines = content.split("\n")
    WV_Map(lines.map{ x =>
             val words = x.trim.split("\t")
             if (words.length != 2)
               throw new EvalException(s"read_tsv ${file}, line has ${words.length} words",
                                       text, docSourceURL)
             WV_String(words(0)) -> WV_String(words(1))
           }.toMap)
  }

  // Object read_object(String|File)
  private def read_object(args : Vector[WV], text : TextSource) : WV_Object =
    throw new EvalException("not implemented", text, docSourceURL)

  // Array[Object] read_objects(String|File)
  private def read_objects(args : Vector[WV], text : TextSource) : WV_Object =
    throw new EvalException("not implemented", text, docSourceURL)

  // mixed read_json(String|File)
  private def read_json(args : Vector[WV], text : TextSource) : WV_Object =
    throw new EvalException("not implemented", text, docSourceURL)

  // Int read_int(String|File)
  //
  private def read_int(args : Vector[WV], text : TextSource) : WV_Int = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    try {
      WV_Int(content.trim.toInt)
    } catch {
      case e : Throwable =>
        throw new EvalException(s"could not convert (${content}) to an integer",
                                text, docSourceURL)
    }
  }

  // String read_string(String|File)
  //
  private def read_string(args : Vector[WV], text : TextSource) : WV_String = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    WV_String(content)
  }

  // Float read_float(String|File)
  //
  private def read_float(args : Vector[WV], text : TextSource) : WV_Float = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    try {
      WV_Float(content.trim.toDouble)
    } catch {
      case e : Throwable =>
        throw new EvalException(s"could not convert (${content}) to a float",
                                text, docSourceURL)
    }
  }

  // Boolean read_boolean(String|File)
  //
  private def read_boolean(args : Vector[WV], text : TextSource) : WV_Boolean = {
    val file = getFile(args, text)
    val content = ioFunctions.readFile(file.value)
    content.trim.toLowerCase() match {
      case "false" => WV_Boolean(false)
      case "true" => WV_Boolean(true)
      case _ =>
        throw new EvalException(s"could not convert (${content}) to a boolean",
                                text, docSourceURL)
    }
  }

  // File write_lines(Array[String])
  //
/*  private def write_lines(args : Vector[WV], text : TextSource) : WV_File = {
  }

      WT_Function1("write_tsv", WT_Array(WT_Array(WT_String)), WT_File),
      WT_Function1("write_map", WT_Map(WT_String, WT_String), WT_File),
      WT_Function1("write_object", WT_Object, WT_File),
      WT_Function1("write_objects", WT_File, WT_Array(WT_File)),
      WT_Function1("write_json", WT_Var(0), WT_File),
      // Size can take several kinds of arguments.
      WT_Function1("size", WT_File, WT_Float),
      WT_Function1("size", WT_Optional(WT_File), WT_Float),
      WT_Function1("size", WT_Array(WT_File), WT_Float),
      WT_Function1("size", WT_Array(WT_Optional(WT_File)), WT_Float),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      WT_Function2("size", WT_File, WT_String, WT_Float),
      WT_Function2("size", WT_Optional(WT_File), WT_String, WT_Float),
      WT_Function2("size", WT_Array(WT_File), WT_String, WT_Float),
      WT_Function2("size", WT_Array(WT_Optional(WT_File)), WT_String, WT_Float),
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


  private def floor(args : Vector[WV], text : TextSource) : WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.floor(x.value).toInt)
  }

  private def ceil(args : Vector[WV], text : TextSource) : WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.ceil(x.value).toInt)
  }

  private def round(args : Vector[WV], text : TextSource) : WV_Int = {
    assert(args.size == 1)
    val x = coercion.coerceTo(WT_Float, args.head, text).asInstanceOf[WV_Float]
    WV_Int(Math.round(x.value).toInt)
  }

  // not mentioned in the specification
  //WT_Function1("glob", WT_String, WT_Array(WT_File))

  def call(funcName : String,
           args : Vector[WV],
           text : TextSource) : WV = {
    if (!(funcTable contains funcName))
      throw new EvalException(s"stdlib function ${funcName} not implemented",
                              text, docSourceURL)
    val impl = funcTable(funcName)
    try {
      impl(args, text)
    } catch {
      case e : Throwable =>
        val msg = s"""|calling stdlib function ${funcName} with arguments ${args}
                      |${e.getMessage}
                      |""".stripMargin
        throw new EvalException(msg, text, docSourceURL)
    }
  }
}
