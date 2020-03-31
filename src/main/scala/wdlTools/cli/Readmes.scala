package wdlTools.cli

import java.net.URI

import wdlTools.generators.{ReadmeGenerator, SspRenderer}
import wdlTools.syntax.{WalkDocuments, walkDocuments}
import wdlTools.util.Util

class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.readmes.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val renderer = SspRenderer()
    WalkDocuments[String](uri, opts, conf.readmes.followImports()) { (uri, doc, results) =>
      ReadmeGenerator(uri, doc, conf.readmes.developerReadmes(), renderer, results).apply()
    }
    Util.writeContentsToFiles(readmes,
                              outputDir = conf.readmes.outputDir.toOption,
                              overwrite = conf.readmes.overwrite())
  }
}
