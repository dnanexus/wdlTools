package wdlTools.cli

import java.nio.file.Files

import wdlTools.formatter._
import wdlTools.util.Util

import collection.JavaConverters._
import scala.language.reflectiveCalls

case class Upgrade(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.upgrade.url()
    val opts = conf.upgrade.getOptions
    val upgrader = Upgrader(opts)

    // write out upgraded versions
    val documents =
      upgrader.upgrade(url, conf.upgrade.srcVersion.toOption, conf.upgrade.destVersion())
    documents.foreach {
      case (uri, lines) =>
        val outputPath =
          Util.getLocalPath(uri, conf.format.outputDir.toOption, conf.format.overwrite())
        Files.write(outputPath, lines.asJava)
    }
  }
}
