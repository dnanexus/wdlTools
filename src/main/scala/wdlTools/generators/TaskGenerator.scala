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

  lazy val formatter: V1_0Formatter = V1_0Formatter(opts)
  lazy val readmeGenerator: ReadmeGenerator =
    ReadmeGenerator(developerReadmes = true, readmes = generatedFiles)

  def generate(model: TaskSpec, outputDir: Option[Path]): Unit = {
    if (interactive) {
      TaskPopulator(opts, wdlVersion, readmes, defaultDockerImage).apply(model)
    }

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

object TaskGenerator {
  case class TaskPopulator(opts: Options,
                           wdlVersion: WdlVersion,
                           readmes: Boolean,
                           defaultDockerImage: String) {
    def apply(model: TaskSpec = TaskSpec()): TaskSpec = {
      val console = GeneratorConsole(opts, wdlVersion)

      if (model.name.isEmpty) {
        model.name = console.askOnce[String](prompt = "Task name")
      }
      if (model.title.isEmpty) {
        model.title = console.askOnce[String](prompt = "Task title", optional = true)
      }
      if (model.summary.isEmpty) {
        model.summary = console.askOnce[String](prompt = "Task summary", optional = true)
      }
      if (model.description.isEmpty && !readmes) {
        model.description = console.askOnce[String](prompt = "Task description", optional = true)
      }
      if (model.docker.isEmpty) {
        model.docker =
          console.askOnce[String](prompt = "Docker image ID", default = Some(defaultDockerImage))
      }

      console.readFields(fieldType = "inputs", fields = model.inputs)

      console.readFields(fieldType = "output", fields = model.outputs)

      model
    }
  }
}
