package wdlTools.cli

import java.nio.file.Files

import wdlTools.generators.code.Upgrader
import dx.util.{FileSourceResolver, LocalFileSource}

import scala.jdk.CollectionConverters._
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
        Files.write(outputDir.get.resolve(source.name), lines.asJava)
      case (localSource: LocalFileSource, lines) if overwrite =>
        Files.write(localSource.canonicalPath, lines.asJava)
      case (_, lines) =>
        println(lines.mkString(System.lineSeparator()))
    }
  }
}
