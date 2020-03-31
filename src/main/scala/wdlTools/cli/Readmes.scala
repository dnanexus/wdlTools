package wdlTools.cli

import java.net.URI

import wdlTools.syntax.walkDocuments
import wdlTools.util.Util

class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.readmes.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val readmes = walkDocuments(uri, opts, conf.readmes.followImports())

  }
}
