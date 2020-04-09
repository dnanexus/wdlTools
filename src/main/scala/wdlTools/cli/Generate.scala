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

    val parent = conf.generate
    parent.subcommand match {
      case parent.task =>
        val model = Model.TaskSpec(
            name = parent.task.name.toOption,
            title = parent.task.title.toOption,
            docker = parent.task.docker.toOption
        )
        val generator = TaskGenerator(
            opts,
            wdlVersion = parent.wdlVersion(),
            interactive = parent.interactive(),
            readmes = parent.readmes(),
            overwrite = parent.overwrite(),
            generatedFiles = generatedFiles
        )
        generator.generate(model, outputDir)
      case parent.workflow =>
        val model = Model.WorkflowSpec(
            parent.wdlVersion(),
            name = parent.workflow.name.toOption,
            title = parent.workflow.title.toOption
        )
        parent.workflow.task().foreach { taskName =>
          model.tasks.append(Model.TaskSpec(Some(taskName)))
        }
        val generator = WorkflowGenerator(
            opts,
            wdlVersion = parent.wdlVersion(),
            interactive = parent.interactive(),
            readmes = parent.readmes(),
            overwrite = parent.overwrite(),
            generatedFiles = generatedFiles
        )
        generator.generate(model, outputDir)
      case parent.project =>
        ProjectGenerator()
      case _ => throw new RuntimeException("Unrecognized subcommand")
    }
  }
}
