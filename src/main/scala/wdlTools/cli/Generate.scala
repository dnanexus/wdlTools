package wdlTools.cli

import java.net.URL
import java.nio.file.Path

import wdlTools.generators.Model.WorkflowSpec
import wdlTools.generators._

import scala.collection.mutable
import scala.language.reflectiveCalls

case class Generate(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty
    val opts = conf.getOptions
    val args = conf.generate
    val outputDir: Option[Path] = args.outputDir.toOption
    val name = args.name()

    val generator = ProjectGenerator(
        opts,
        name,
        wdlVersion = args.wdlVersion(),
        interactive = args.interactive(),
        readmes = args.readmes(),
        overwrite = args.overwrite(),
        generatedFiles = generatedFiles,
        dockerImage = args.docker.toOption
    )

    val workflow = if (args.workflow()) {
      Some(Model.WorkflowSpec(args.wdlVersion(), name = Some(name)))
    } else {
      None
    }

    val tasks = args.task().map { taskName =>
      Model.TaskSpec(Some(taskName))
    }

    generator.apply(workflow, tasks)
  }
}
