package wdlTools.cli

import wdlTools.syntax.Parsers
import wdlTools.types.{Context, Stdlib, TypeEval}

case class TypeCheck(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.check.url()
    val opts = conf.check.getOptions
    val parsers = Parsers(opts)
    val checker = TypeEval(Stdlib(opts))

    parsers.getDocumentWalker[Context](url).walk { (_, doc, _) =>
      // TODO: rather than throw an exception as soon as a type error is encountered, accumulate all errors
      // and print them out at the end
      checker.apply(doc)
    }
  }
}
