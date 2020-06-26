package wdlTools.cli

import java.nio.file.Files

import wdlTools.generators.project.DocumentationGenerator
import wdlTools.util.Util

import scala.language.reflectiveCalls

case class Docgen(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val outputDir = conf.docgen.outputDir()
    val overwrite = conf.docgen.overwrite()
    if (!overwrite && Files.exists(outputDir)) {
      throw new Exception(s"File already exists: ${outputDir}")
    }
    val url = conf.docgen.url()
    val opts = conf.docgen.getOptions
    val title = conf.docgen.title.getOrElse(outputDir.getFileName.toString)
    val docgen = DocumentationGenerator(opts)
    val pages = docgen.apply(url, title)
    Util.writeFilesContents(pages.map {
      case (filename, contents) => outputDir.resolve(filename) -> contents
    }, overwrite)
  }
}
