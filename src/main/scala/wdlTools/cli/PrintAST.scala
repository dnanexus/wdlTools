package wdlTools.cli

import wdlTools.syntax.Parsers
import wdlTools.util.Util

case class PrintAST(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.check.url()
    val opts = conf.check.getOptions(Set(Util.getLocalPath(url).getParent))
    val parsers = Parsers(opts)
    val document = parsers.parse(url)
    println(Util.prettyFormat(document))
  }
}
