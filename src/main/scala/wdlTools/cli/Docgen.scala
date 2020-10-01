package wdlTools.cli

import java.nio.file.Files

import wdlTools.generators.project.DocumentationGenerator
import wdlTools.util.{FileNode, FileSourceResolver}

import scala.language.reflectiveCalls

case class Docgen(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val outputDir = conf.docgen.outputDir()
    val overwrite = conf.docgen.overwrite()
    if (!overwrite && Files.exists(outputDir)) {
      throw new Exception(s"File already exists: ${outputDir}")
    }
    val docSource = FileSourceResolver.get.resolve(conf.docgen.uri())
    val title = conf.docgen.title.getOrElse(outputDir.getFileName.toString)
    val pages = DocumentationGenerator.apply(docSource, title, conf.docgen.followImports())
    FileNode.localizeAll(pages, Some(outputDir), overwrite)
  }
}
