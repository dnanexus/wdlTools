package wdlTools.cli

import java.net.URI

import wdlTools.syntax.ParseAll
import wdlTools.typechecker.{Checker, Stdlib}

import scala.language.reflectiveCalls

case class Check(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.check.uri())
    val (uriLocalPath, sourceCode) = Util.readFromUri(uri, conf)
    val parser = ParseAll(conf.getSyntaxConf(Set(uriLocalPath.getParent)))
    val document = parser.apply(sourceCode)
    val checker = Checker(Stdlib())
    checker.apply(document)
  }
}
