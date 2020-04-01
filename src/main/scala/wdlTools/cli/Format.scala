package wdlTools.cli

import java.net.URI
import java.nio.file.Files

import wdlTools.formatter._
import wdlTools.util.Util

import collection.JavaConverters._
import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.format.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val formatter = WDL10Formatter(opts)

    formatter.formatDocuments(uri, followImports = conf.format.followImports())
    formatter.documents.foreach {
      case (uri, lines) =>
        val outputPath =
          Util.getLocalPath(uri, conf.format.outputDir.toOption, conf.format.overwrite())
        Files.write(outputPath, lines.asJava)
    }
  }
}
