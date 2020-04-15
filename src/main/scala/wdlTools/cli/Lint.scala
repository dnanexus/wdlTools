package wdlTools.cli

import wdlTools.linter.Linter

case class Lint(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.format.url()
    val opts = conf.format.getOptions
    val linter = Linter(opts)
    linter.apply(url)
  }
}
