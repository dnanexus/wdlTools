package wdlTools.eval

import java.net.URL
import wdlTools.syntax.TextSource
import wdlTools.util.Options
import WdlValues._

case class StdlibDraft2(opts: Options, evalCfg: EvalConfig, docSourceUrl: Option[URL])
    extends StandardLibraryImpl {

  def call(funcName: String, elements: Vector[V], text: TextSource): V = ???
}
