package wdlTools.cli

import java.net.URL

import wdlTools.generators.Renderer
import wdlTools.generators.project.ReadmeGenerator
import wdlTools.syntax.Parsers
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.readmes.url()
    val parsers = Parsers(conf.readmes.getOptions)
    val renderer = Renderer()
    val readmes = parsers.getDocumentWalker[Map[URL, String]](url, Map.empty).walk {
      (doc, results) =>
        results ++ ReadmeGenerator(conf.readmes.developerReadmes(), renderer).apply(doc)
    }
    Util.writeUrlContents(readmes,
                          outputDir = conf.readmes.outputDir.toOption,
                          overwrite = conf.readmes.overwrite())
  }
}
