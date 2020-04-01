package wdlTools.cli

import java.net.URI

import wdlTools.syntax.ParseAll
import wdlTools.util.{URL, Util}

import scala.language.reflectiveCalls

case class PrintAST(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.check.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val sourceUrl = URL("file://" + uriLocalPath.toString)
    val parser = ParseAll(opts)
    val document = parser.apply(sourceUrl)
    println(Util.prettyPrint(document))
  }
}
