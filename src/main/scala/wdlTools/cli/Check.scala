package wdlTools.cli

import wdlTools.syntax.Parsers
import wdlTools.typechecker.{Checker, Stdlib}
import wdlTools.util.Util

case class Check(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.check.url()
    val opts = conf.check.getOptions(Set(Util.getLocalPath(url).getParent))
    val parsers = Parsers(opts)
    val checker = Checker(Stdlib(opts))

    parsers.getDocumentWalker[checker.Context](url).walk { (_, doc, _) =>
      // TODO: rather than throw an exception as soon as a type error is encountered, accumulate all errors
      // and print them out at the end
      checker.apply(doc)
    }
  }
}
