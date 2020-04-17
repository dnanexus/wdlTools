package wdlTools.cli

import java.nio.file.Files

import wdlTools.formatter._
import wdlTools.util.Util

import collection.JavaConverters._
import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val url = conf.format.url()
    val opts = conf.format.getOptions
    val formatter = WdlV1Formatter(opts)
    formatter.formatDocuments(url)
    formatter.documents.foreach {
      case (uri, lines) =>
        val outputPath =
          Util.getLocalPath(uri, conf.format.outputDir.toOption, conf.format.overwrite())
        Files.write(outputPath, lines.asJava)
    }
  }
}
