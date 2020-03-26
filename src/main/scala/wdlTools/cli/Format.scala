package wdlTools.cli

import java.net.URI
import java.nio.file.Files

import wdlTools.formatter._
import wdlTools.syntax.ParseAll
import wdlTools.util.{FetchURL, Util}

import collection.JavaConverters._
import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val uri = new URI(conf.check.uri())
    val uriLocalPath = Util.getLocalPath(uri)
    val opts = conf.getSyntaxOptions(Set(uriLocalPath.getParent))
    val sourceCode = FetchURL.readFromUri(uri, opts)
    val parser = ParseAll(opts)
    val document = parser.apply(sourceCode)

    val formatter = WDL10Formatter(conf.verbosity)
    formatter.formatDocument(uri, document, followImports = conf.format.followImports())

    formatter.documents.foreach {
      case (uri, lines) =>
        val outputPath =
          Util.getLocalPath(uri, conf.format.outputDir.toOption, conf.format.overwrite())
        Files.write(outputPath, lines.asJava)
    }
  }
}
