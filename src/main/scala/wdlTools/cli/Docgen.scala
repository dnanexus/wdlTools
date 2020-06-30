package wdlTools.cli

import java.nio.file.Files

import wdlTools.generators.project.DocumentationGenerator
import wdlTools.util.FileSource

import scala.language.reflectiveCalls

case class Docgen(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val outputDir = conf.docgen.outputDir()
    val overwrite = conf.docgen.overwrite()
    if (!overwrite && Files.exists(outputDir)) {
      throw new Exception(s"File already exists: ${outputDir}")
    }
    val opts = conf.docgen.getOptions
    val docSource = opts.fileResolver.resolve(conf.docgen.uri())
    val title = conf.docgen.title.getOrElse(outputDir.getFileName.toString)
    val docgen = DocumentationGenerator(opts)
    val pages = docgen.apply(docSource, title)
    FileSource.localizeAll(pages, Some(outputDir), overwrite)
  }
}
