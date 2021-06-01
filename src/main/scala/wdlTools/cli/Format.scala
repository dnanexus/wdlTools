package wdlTools.cli

import wdlTools.generators.code.WdlFormatter
import dx.util.FileSourceResolver

import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.format.uri())
    val outputDir = conf.format.outputDir.toOption
    val overwrite = conf.format.overwrite()
    val followImports = conf.format.followImports()
    val formatter = WdlFormatter(followImports = followImports)
    val documents = formatter.formatDocuments(docSource)
    writeDocuments(documents, outputDir, overwrite)
  }
}
