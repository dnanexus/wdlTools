package wdlTools.cli

import wdlTools.generators.TaskGenerator

import scala.language.reflectiveCalls

case class Generate(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    conf.generate.subcommand match {
      case conf.generate.task =>
        val task = conf.generate.task
        TaskGenerator(
            TaskGenerator.Model(
                version = conf.generate.wdlVersion(),
                name = task.name.toOption,
                title = task.title.toOption,
                docker = task.docker.toOption
            ),
            interactive = conf.generate.interactive()
        )
      case conf.generate.workflow =>
      case conf.generate.project  =>
    }
  }
}
