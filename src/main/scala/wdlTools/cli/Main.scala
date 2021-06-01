package wdlTools.cli

import dx.util.Logger

/**
  * Base class for wdlTools CLI commands.
  */
trait Command {
  def apply(): Unit
}

object Main extends App {
  val conf = new WdlToolsConf(args.toVector)

  def runCommand: Boolean = {
    conf.subcommand match {
      case None =>
        conf.printHelp()
        true
      case Some(subcommand: InitializableSubcommand) =>
        try {
          subcommand.init()
          val command: Command = subcommand match {
            case conf.check     => TypeCheck(conf)
            case conf.docgen    => Docgen(conf)
            case conf.exec      => Exec(conf)
            case conf.format    => Format(conf)
            case conf.lint      => Lint(conf)
            case conf.upgrade   => Upgrade(conf)
            case conf.fix       => Fix(conf)
            case conf.generate  => Generate(conf)
            case conf.readmes   => Readmes(conf)
            case conf.printTree => PrintTree(conf)
            case other          => throw new Exception(s"Unrecognized command $other")
          }
          command.apply()
          false
        } catch {
          case t: Throwable =>
            Logger.error(s"Command ${subcommand.printedName} failed", Some(t))
            true
        }
      case other =>
        throw new RuntimeException(s"Invalid subcommand ${other}")
    }
  }

  val error = runCommand
  if (error) {
    System.exit(1)
  }
}
