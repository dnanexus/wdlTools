package wdlTools.cli

import wdlTools.util.Logger

object Main extends App {
  val conf = new WdlToolsConf(args.toVector)

  def runCommand: Int = {
    conf.subcommand match {
      case None =>
        conf.printHelp()
        1
      case Some(subcommand) =>
        val command: Command = subcommand match {
          case conf.check     => TypeCheck(conf)
          case conf.docgen    => Docgen(conf)
          case conf.exec      => Exec(conf)
          case conf.format    => Format(conf)
          case conf.lint      => Lint(conf)
          case conf.upgrade   => Upgrade(conf)
          case conf.generate  => Generate(conf)
          case conf.readmes   => Readmes(conf)
          case conf.printTree => PrintTree(conf)
          case other =>
            Logger.error(s"Unrecognized command $other")
            return 1
        }
        try {
          command.apply()
          0
        } catch {
          case t: Throwable =>
            Logger.error(s"Command ${subcommand.printedName} failed", Some(t))
            1
        }
    }
  }

  System.exit(runCommand)
}
