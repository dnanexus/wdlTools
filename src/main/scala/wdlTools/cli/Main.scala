package wdlTools.cli

import java.nio.file.{Path, Paths}

import org.rogach.scallop.{ScallopConf, ScallopOption, Subcommand, ValueConverter, listArgConverter}

object Main extends App {
  implicit val fileListConverter: ValueConverter[List[Path]] = listArgConverter[Path](Paths.get(_))

  class Conf(args: Seq[String]) extends ScallopConf(args) {
    version(s"wdlTools ${Utils.version()}")
    banner("""Usage: wdlTools <COMMAND> [OPTIONS]
             |Options:
             |""".stripMargin)

    val verbose: ScallopOption[Boolean] = opt[Boolean](descr = "use more verbose output")

    val format = new Subcommand("format") {
      banner("""Usage: wdlTools format [OPTIONS] <path|uri>
               |Reformat WDL file and all its dependencies according to style rules.
               |
               |Options:
               |""".stripMargin)

      val followImports: ScallopOption[Boolean] = toggle(
          descrYes = "format imported files in addition to the main file",
          descrNo = "only format the main file",
          default = Some(true)
      )
      val importDir: ScallopOption[List[Path]] =
        opt[List[Path]](descr =
          "directory in which to search for imports; ignored if --noImports is specified"
        )
      val wdlVersion: ScallopOption[String] = opt[String](
          descr = "WDL version to generate; currently only v1.0 is supported",
          default = Some("1.0")
      )
      validateOpt(wdlVersion) {
        case Some(version) if version != "1.0" => Left("Only WDL v1.0 is supported currently")
        case _                                 => Right(Unit)
      }
      val outputDir: ScallopOption[Path] = opt[Path](descr =
        "Directory in which to output formatted WDL files; if not specified, the input files are overwritten"
      )
      val overwrite: ScallopOption[Boolean] = toggle(default = Some(false))
      validateOpt(outputDir, overwrite) {
        case (None, Some(false) | None) =>
          Left("--outputDir is required unless --overwrite is specified")
        case _ => Right(Unit)
      }
      val uri: ScallopOption[String] =
        trailArg[String](descr = "path or URI (file:// or http(s)://) to the main WDL file")
    }
    addSubcommand(format)

    val printAST = new Subcommand("printAST") {
      banner("""Usage: wdlTools printAST [OPTIONS] <path|uri> 
               |Print the Abstract Syntax Tree for a WDL file.
               |
               |Options:
               |""".stripMargin)
      val importDir: ScallopOption[List[Path]] =
        opt[List[Path]](descr =
          "directory in which to search for imports; ignored if --noImports is specified"
        )
      val uri: ScallopOption[String] =
        trailArg[String](descr = "path or URI (file:// or http(s)://) to the main WDL file")
    }
    addSubcommand(printAST)

    verify()
  }

  val conf = new Conf(args)
  val verbose = conf.verbose.getOrElse(default = false)

  conf.subcommand match {
    case None => conf.printHelp()
    case Some(subcommand) =>
      val command: Command = subcommand match {
        case other => throw new Exception(s"Unrecognized command $other")
      }
      command.apply()
  }
}
