package wdlTools.cli

import wdlTools.syntax.{AbstractSyntax, Parsers}
import wdlTools.types.{TypeInfer, TypeOptions, TypedAbstractSyntax}
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class PrintTree(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.printTree.url()
    val opts = conf.printTree.getOptions
    val parsers = Parsers(opts)
    val document = parsers.parseDocument(url)
    if (conf.printTree.typed()) {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: TypedAbstractSyntax.Document if d.docSourceUrl != url => Some("...")
          case _                                                        => None
        }
      }
      val typeChecker = TypeInfer(
          TypeOptions(opts.localDirectories, opts.verbosity, opts.antlr4Trace)
      )
      println(Util.prettyFormat(typeChecker.apply(document)._1, callback = Some(ignoreImports)))
    } else {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: AbstractSyntax.Document if d.sourceUrl != url => Some("...")
          case _                                                => None
        }
      }
      println(Util.prettyFormat(document, callback = Some(ignoreImports)))
    }
  }
}
