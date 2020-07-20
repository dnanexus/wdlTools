package wdlTools.cli

import wdlTools.syntax.{AbstractSyntax, Parsers}
import wdlTools.types.{TypeInfer, TypedAbstractSyntax}
import wdlTools.util.{FileSourceResolver, prettyFormat}

import scala.language.reflectiveCalls

case class PrintTree(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.printTree.uri())
    val parsers = Parsers(followImports = false)
    val document = parsers.parseDocument(docSource)
    if (conf.printTree.typed()) {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: TypedAbstractSyntax.Document if d.source != document.source => Some("...")
          case _                                                              => None
        }
      }
      val typeChecker = TypeInfer(conf.printTree.regime())
      println(
          prettyFormat(typeChecker.apply(document)._1, callback = Some(ignoreImports))
      )
    } else {
      def ignoreImports(p: Product): Option[String] = {
        p match {
          case d: AbstractSyntax.Document if d.source != document.source => Some("...")
          case _                                                         => None
        }
      }
      println(prettyFormat(document, callback = Some(ignoreImports)))
    }
  }
}
