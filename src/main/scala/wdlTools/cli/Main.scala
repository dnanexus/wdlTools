package wdlTools.cli

import wdlTools.cli.Util.Conf

object Main extends App {
  val conf = new Conf(args)

  conf.subcommand match {
    case None => conf.printHelp()
    case Some(subcommand) =>
      val command: Command = subcommand match {
        case conf.check => Check(conf)
        case other      => throw new Exception(s"Unrecognized command $other")
      }
      command.apply()
  }
}
