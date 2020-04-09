package wdlTools.generators

import java.net.URL
import java.nio.file.{Files, Path, Paths}

import wdlTools.formatter.V1_0Formatter
import wdlTools.generators.Model._
import wdlTools.syntax.WdlVersion
import wdlTools.util.{Options, Util}

import scala.collection.mutable

case class WorkflowGenerator(opts: Options,
                             wdlVersion: WdlVersion = WdlVersion.V1_0,
                             interactive: Boolean = false,
                             readmes: Boolean = false,
                             overwrite: Boolean = false,
                             defaultDockerImage: String = "debian:stretch-slim",
                             generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty) {

  lazy val formatter: V1_0Formatter = V1_0Formatter(opts)
  lazy val readmeGenerator: ReadmeGenerator =
    ReadmeGenerator(developerReadmes = true, readmes = generatedFiles)
  lazy val taskPopulator: TaskGenerator.TaskPopulator =
    TaskGenerator.TaskPopulator(opts, wdlVersion, readmes, defaultDockerImage)

  def populateWorkflow(model: Model.WorkflowSpec): Unit = {
    val console = GeneratorConsole(opts, wdlVersion)

    if (model.name.isEmpty) {
      model.name = console.askOnce[String](prompt = "Workflow name")
    }
    if (model.title.isEmpty) {
      model.title = console.askOnce[String](prompt = "Workflow title", optional = true)
    }
    if (model.summary.isEmpty) {
      model.summary = console.askOnce[String](prompt = "Workflow summary", optional = true)
    }
    if (model.description.isEmpty && !readmes) {
      model.description = console.askOnce[String](prompt = "Workflow description", optional = true)
    }

    console.readFields(fieldType = "input", fields = model.inputs)

    console.readFields(fieldType = "output", fields = model.outputs)

    model.tasks.foreach { task =>
      taskPopulator.apply(task)
    }

    while (console.askYesNo("Add a task?", default = Some(false))) {
      model.tasks.append(taskPopulator.apply())
    }
  }

  def generate(model: WorkflowSpec, outputDir: Option[Path]): Unit = {
    if (interactive) {
      populateWorkflow(model)
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

    val doc = model.toDocument

    generatedFiles(url) = formatter.formatDocument(doc).mkString(System.lineSeparator())

    if (readmes) {
      readmeGenerator.apply(url, doc)
    }
  }
}
