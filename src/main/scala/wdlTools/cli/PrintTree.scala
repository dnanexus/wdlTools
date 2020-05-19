package wdlTools.cli

import wdlTools.syntax.Parsers
import wdlTools.types.TypeInfer
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class PrintTree(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.printTree.url()
    val opts = conf.printTree.getOptions
    val parsers = Parsers(opts)
    val document = parsers.parseDocument(url)
    val doc: Any = if (conf.printTree.typed()) {
      val typeChecker = TypeInfer(opts)
      typeChecker.apply(document)._1
    } else {
      document
    }
    println(Util.prettyFormat(doc))
  }
}
