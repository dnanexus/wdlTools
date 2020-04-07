package wdlTools.cli

import java.nio.file.Path

import wdlTools.generators._

import scala.collection.mutable
import scala.language.reflectiveCalls

case class Generate(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val generatedFiles: mutable.Map[Path, String] = mutable.HashMap.empty
    val outputDir: Option[Path] = conf.generate.outputDir.toOption
    val opts = conf.getOptions

    conf.generate.subcommand match {
      case task @ conf.generate.task =>
        val model = TaskGenerator.Model(
            version = conf.generate.wdlVersion(),
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
        WorkflowGenerator()
      case project @ conf.generate.project =>
        ProjectGenerator()
    }
  }
}
