package wdlTools.eval

import java.net.URL
import wdlTools.syntax.TextSource
import wdlTools.util.{EvalConfig, Options}
import WdlValues._

case class StdlibDraft2(opts : Options,
                        evalCfg: EvalConfig,
                        docSourceURL : Option[URL]) extends StandardLibraryImpl {
  val ioFunctions = IoFunctions(opts, evalCfg)

  def call(funcName : String,
           elements : Vector[WV],
           text : TextSource) : WV = ???
}
