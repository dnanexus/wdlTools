package wdlTools.cli

import wdlTools.generators.{ReadmeGenerator, SspRenderer}
import wdlTools.syntax.Parsers
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.readmes.url()
    val opts = conf.readmes.getOptions(Set(Util.getLocalPath(url).getParent))
    val parsers = Parsers(opts)
    val renderer = SspRenderer()
    val readmes = parsers.getDocumentWalker[String](url).walk { (url, doc, results) =>
      ReadmeGenerator(url, doc, conf.readmes.developerReadmes(), renderer, results).apply()
    }
    Util.writeContentsToFiles(readmes,
                              outputDir = conf.readmes.outputDir.toOption,
                              overwrite = conf.readmes.overwrite())
  }
}
