package wdlTools.cli

import java.net.URL
import java.nio.file.Path

import wdlTools.generators._

import scala.collection.mutable
import scala.language.reflectiveCalls

case class Generate(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty
    val outputDir: Option[Path] = conf.generate.outputDir.toOption
    val opts = conf.getOptions

    conf.generate.subcommand match {
      case task @ conf.generate.task =>
        val model = Model.TaskSpec(
            name = task.name.toOption,
            title = task.title.toOption,
            docker = task.docker.toOption
        )
        val generator = TaskGenerator(
            opts,
            wdlVersion = conf.generate.wdlVersion(),
            interactive = conf.generate.interactive(),
            readmes = conf.generate.readmes(),
            overwrite = conf.generate.overwrite(),
            generatedFiles = generatedFiles
        )
        generator.generate(model, outputDir)
      case workflow @ conf.generate.workflow =>
        val model = Model.WorkflowSpec(
            conf.generate.wdlVersion(),
            name = workflow.name.toOption,
            title = workflow.title.toOption
        )
        val generator = WorkflowGenerator(
            opts,
            wdlVersion = conf.generate.wdlVersion(),
            interactive = conf.generate.interactive(),
            readmes = conf.generate.readmes(),
            overwrite = conf.generate.overwrite(),
            generatedFiles = generatedFiles
        )
        generator.generate(model, outputDir)
      case project @ conf.generate.project =>
        ProjectGenerator()
    }
  }
}
