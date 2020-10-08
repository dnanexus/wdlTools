package wdlTools.cli

import java.nio.file.{FileAlreadyExistsException, Files}

import wdlTools.generators.project.DocumentationGenerator
import wdlTools.util.{FileSourceResolver, FileUtils}

import scala.language.reflectiveCalls

case class Docgen(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val outputDir = conf.docgen.outputDir().toAbsolutePath
    val overwrite = conf.docgen.overwrite()
    if (!overwrite && Files.exists(outputDir)) {
      throw new Exception(s"Directory ${outputDir} already exists and overwrite = false")
    }
    val docSource = FileSourceResolver.get.resolve(conf.docgen.uri())
    val title = conf.docgen.title.getOrElse(outputDir.getFileName.toString)
    val pages = DocumentationGenerator.apply(docSource, title, conf.docgen.followImports())
    pages.foreach {
      case (name, content) =>
        val path = outputDir.resolve(name)
        if (!overwrite && Files.exists(path)) {
          throw new FileAlreadyExistsException(
              s"File ${path} already exists and overwrite = false"
          )
        }
        FileUtils.writeFileContent(path, content)
    }
  }
}
