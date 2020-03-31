package wdlTools.cli

import java.net.URI

import wdlTools.syntax.ParseAll
import wdlTools.typechecker.{Checker, Stdlib}
import wdlTools.util.{FetchURL, Util}

import scala.language.reflectiveCalls

class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.readmes.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val sourceCode = FetchURL.readFromUri(uri, opts)
    val parser = ParseAll(opts)
    val document = parser.apply(sourceCode)

  }
}
