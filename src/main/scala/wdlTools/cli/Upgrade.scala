package wdlTools.cli

import wdlTools.generators.code.Upgrader
import dx.util.{FileSourceResolver, FileUtils, LocalFileSource}

import scala.language.reflectiveCalls

case class Upgrade(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.upgrade.uri())
    val srcVersion = conf.upgrade.srcVersion.toOption
    val destVersion = conf.upgrade.destVersion()
    val outputDir = conf.upgrade.outputDir.toOption
    val overwrite = conf.upgrade.overwrite()
    val upgrader = Upgrader(conf.upgrade.followImports())
    // write out upgraded versions
    val documents = upgrader.upgrade(docSource, srcVersion, destVersion)
    documents.foreach {
      case (source, lines) if outputDir.isDefined =>
        FileUtils.writeFileContent(outputDir.get.resolve(source.name),
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = overwrite)
      case (localSource: LocalFileSource, lines) if overwrite =>
        FileUtils.writeFileContent(localSource.canonicalPath,
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = overwrite)
      case (_, lines) =>
        println(lines.mkString(System.lineSeparator()))
    }
  }
}
