package wdlTools.eval.stdlib

import java.net.URL

import wdlTools.eval.EvalConfig
import wdlTools.util.Options

case class StdlibV2(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL])
    extends BaseStdlib(opts, evalCfg, docSourceUrl) {

  override protected val funcTable: Map[String, FunctionImpl] = Map(
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
      "glob" -> glob
  )
}
