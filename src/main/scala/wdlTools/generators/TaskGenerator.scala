package wdlTools.generators

import java.net.URL
import java.nio.file.{Files, Path, Paths}

import wdlTools.formatter.V1_0Formatter
import wdlTools.generators.Model._
import wdlTools.generators.TaskGenerator.TaskPopulator
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.WdlVersion
import wdlTools.util.{Options, Util}

import scala.collection.mutable

case class TaskGenerator(opts: Options,
                         wdlVersion: WdlVersion = WdlVersion.V1_0,
                         interactive: Boolean = false,
                         readmes: Boolean = false,
                         overwrite: Boolean = false,
                         defaultDockerImage: String = "debian:stretch-slim",
                         generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty) {

  def generateTask(model: TaskSpec, fname: String): Unit = {

    val fname = s"${model.name}.wdl"
    val outputPath = if (outputDir.isDefined) {
      outputDir.get.resolve(fname)
    } else {
      Paths.get(fname)
    }
    val url = Util.getURL(outputPath)
    if (!overwrite && (generatedFiles.contains(url) || Files.exists(outputPath))) {
      throw new Exception(
          s"File ${outputPath} already exists; use --overwrite if you want to overwrite it"
      )
    }

    val doc = Document(wdlVersion, null, Vector(model.toTask), None, null, None)

    generatedFiles(url) = formatter.formatDocument(doc).mkString(System.lineSeparator())

    if (readmes) {
      readmeGenerator.apply(url, doc)
    }
  }
}
