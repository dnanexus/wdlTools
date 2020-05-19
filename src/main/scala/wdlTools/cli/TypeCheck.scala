package wdlTools.cli

import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.{TypeException, TypeInfer}

case class TypeCheck(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.check.url()
    val opts = conf.check.getOptions
    val parsers = Parsers(opts)
    val checker = TypeInfer(opts)
    try {
      checker.apply(parsers.parseDocument(url))
    } catch {
      case e: SyntaxException => println(s"Failed to parse WDL document: ${e.getMessage}")
      // TODO: accumulate all errors
      case e: TypeException => println(s"Failed to type-check WDL document: ${e.getMessage}")
    }
  }
}
