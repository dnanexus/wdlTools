package wdlTools.cli

import wdlTools.generators.Renderer
import wdlTools.generators.project.ReadmeGenerator
import wdlTools.syntax.Parsers
import wdlTools.util.FileSource

import scala.language.reflectiveCalls

case class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val opts = conf.readmes.getOptions
    val docSource = opts.fileResolver.resolve(conf.readmes.uri())
    val parsers = Parsers(opts)
    val renderer = Renderer()
    val readmes = parsers.getDocumentWalker[Vector[FileSource]](docSource, Vector.empty).walk {
      (doc, results) =>
        results ++ ReadmeGenerator(conf.readmes.developerReadmes(), renderer).apply(doc)
    }
    FileSource.localizeAll(readmes,
                           outputDir = conf.readmes.outputDir.toOption,
                           overwrite = conf.readmes.overwrite())
  }
}
