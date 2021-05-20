package wdlTools.cli

import wdlTools.generators.code.WdlFormatter
import dx.util.{FileSourceResolver, FileUtils, LocalFileSource}

import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.format.uri())
    val outputDir = conf.format.outputDir.toOption
    val overwrite = conf.format.overwrite()
    val formatter = WdlFormatter(followImports = conf.format.followImports())
    val documents = formatter.formatDocuments(docSource)
    documents.foreach {
      case (source, lines) if outputDir.isDefined =>
        FileUtils.writeFileContent(outputDir.get.resolve(source.name),
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = overwrite)
      case (localSource: LocalFileSource, lines) if overwrite =>
        FileUtils.writeFileContent(localSource.canonicalPath,
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = true)
      case (_, lines) =>
        println(lines.mkString(System.lineSeparator()))
    }
  }
}
