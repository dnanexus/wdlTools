package wdlTools.cli

import java.net.URI

import wdlTools.syntax.WalkDocuments
import wdlTools.typechecker.{Checker, Stdlib}
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class Check(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.check.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val checker = Checker(Stdlib(opts))
    // TODO: once imports are supported, set followImports to true or allow user to set on the command line
    WalkDocuments[Boolean](uri, opts, followImports = false).apply { (_, doc, _) =>
      // TODO: rather than throw an exception as soon as a type error is encountered, accumulate all errors
      // and print them out at the end
      checker.apply(doc)
    }
  }
}
