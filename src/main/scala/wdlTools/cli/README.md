# wdlTools CLI

## Adding a new command

1. Create a new class in a file with the same name as the command:
    ```scala
    package wdlTools.cli
   
    import scala.language.reflectiveCalls
    
    case class MyCommand(conf: WdlToolsConf) extends Command {
      override def apply(): Unit = {
          ...
      }
    }
    ```
2. In `package`, add a new subcommand definition:
    ```scala
    val mycommand = new Subcommand("mycommand") {
        banner("""Usage: wdlTools mycommand [OPTIONS] <path|uri>
                 |Does some cool stuff.
                 |
                 |Options:
                 |""".stripMargin)
        ...
    ```
3. In `Main`, add your command to the pattern matcher:
    ```scala
    conf.subcommand match {
       case None => conf.printHelp()
       case Some(subcommand) =>
         val command: Command = subcommand match {
           case conf.mycommand => MyCommand(conf)
           ...
           case other          => throw new Exception(s"Unrecognized command $other")
         }
         command.apply()
    }
    ```