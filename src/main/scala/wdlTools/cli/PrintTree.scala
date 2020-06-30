package wdlTools.cli

import wdlTools.syntax.{AbstractSyntax, Parsers}
import wdlTools.types.{TypeInfer, TypeOptions, TypedAbstractSyntax}
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class PrintTree(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val opts = conf.printTree.getOptions
    val docSource = opts.fileResolver.resolve(conf.printTree.uri())
    val parsers = Parsers(opts)
    val document = parsers.parseDocument(docSource)
    if (conf.printTree.typed()) {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: TypedAbstractSyntax.Document if d.source != document.source => Some("...")
          case _                                                              => None
        }
      }
      val typeChecker = TypeInfer(
          TypeOptions(fileResolver = opts.fileResolver,
                      logger = opts.logger,
                      antlr4Trace = opts.antlr4Trace)
      )
      println(Util.prettyFormat(typeChecker.apply(document)._1, callback = Some(ignoreImports)))
    } else {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: AbstractSyntax.Document if d.source != document.source => Some("...")
          case _                                                         => None
        }
      }
      println(Util.prettyFormat(document, callback = Some(ignoreImports)))
    }
  }
}
