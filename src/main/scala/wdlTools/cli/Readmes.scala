package wdlTools.cli

import java.net.URI

import wdlTools.generators.{ReadmeGenerator, SspRenderer}
import wdlTools.syntax.WalkDocuments
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.readmes.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val renderer = SspRenderer()
    val readmes = WalkDocuments[String](uri, opts, conf.readmes.followImports()).apply {
      (uri, doc, results) =>
        ReadmeGenerator(uri, doc, conf.readmes.developerReadmes(), renderer, results).apply()
    }
    Util.writeContentsToFiles(readmes,
                              outputDir = conf.readmes.outputDir.toOption,
                              overwrite = conf.readmes.overwrite())
  }
}
