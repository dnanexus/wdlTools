package wdlTools.cli

import java.net.URI

import wdlTools.generators.SspRenderer
import wdlTools.syntax.walkDocuments
import wdlTools.util.Util

class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.readmes.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val renderer = SspRenderer()
    val readmes = walkDocuments(uri, opts, conf.readmes.followImports()) { doc =>
      renderer.generate()
    }
    Util.writeFiles(readmes,
                    outputDir = conf.readmes.outputDir.toOption(),
                    overwrite = conf.readmes.overwrite())
  }
}
