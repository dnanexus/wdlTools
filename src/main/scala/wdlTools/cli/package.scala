package wdlTools.cli

import java.net.URL
import java.nio.file.{Path, Paths}

import org.rogach.scallop.{
  ScallopConf,
  ScallopOption,
  Subcommand,
  ValueConverter,
  listArgConverter,
  singleArgConverter
}
import wdlTools.syntax.WdlVersion
import wdlTools.util.Verbosity._
import wdlTools.util.{Options, Util}

/**
  * Base class for wdlTools CLI commands.
  */
trait Command {
  def apply(): Unit
}

class WdlToolsConf(args: Seq[String]) extends ScallopConf(args) {
  implicit val fileListConverter: ValueConverter[List[Path]] = listArgConverter[Path](Paths.get(_))
  implicit val urlConverter: ValueConverter[URL] = singleArgConverter[URL](Util.getURL(_))
  implicit val versionConverter: ValueConverter[WdlVersion] =
    singleArgConverter[WdlVersion](WdlVersion.fromName)

  class ParserSubcommand(name: String, description: String) extends Subcommand(name) {
    // there is a compiler bug that prevents accessing name directly
    banner(s"""Usage: wdlTools ${commandNameAndAliases.head} [OPTIONS] <path|uri>
              |${description}
              |
              |Options:
              |""".stripMargin)
    val followImports: ScallopOption[Boolean] = toggle(
        descrYes = "format imported files in addition to the main file",
        descrNo = "only format the main file",
        default = Some(true)
    )
    val localDir: ScallopOption[List[Path]] =
      opt[List[Path]](descr =
        "directory in which to search for imports; ignored if --noFollowImports is specified"
      )
    val url: ScallopOption[URL] =
      trailArg[URL](descr = "path or URL (file:// or http(s)://) to the main WDL file")

    /**
      * The local directories to search for WDL imports.
      * @param merge a Set of Paths to merge in with the local directories.
      * @return
      */
    def localDirectories(merge: Set[Path] = Set.empty): Vector[Path] = {
      if (this.localDir.isDefined) {
        (this.localDir().toSet ++ merge).toVector
      } else {
        merge
      }.toVector
    }

    /**
      * Gets a syntax.Util.Options object based on the command line options.
      * @return
      */
    def getOptions: Options = {
      val parentConf = this.parentConfig.asInstanceOf[WdlToolsConf]
      val wdlDir: Path = Util.getLocalPath(url()).getParent
      Options(
          localDirectories = Some(this.localDirectories(Set(wdlDir))),
          followImports = this.followImports(),
          verbosity = parentConf.verbosity,
          antlr4Trace = parentConf.antlr4Trace.getOrElse(default = false)
      )
    }
  }

  version(s"wdlTools ${Util.getVersion}")
  banner("""Usage: wdlTools <COMMAND> [OPTIONS]
           |Options:
           |""".stripMargin)

  val verbose: ScallopOption[Boolean] = toggle(descrYes = "use more verbose output")
  val quiet: ScallopOption[Boolean] = toggle(descrYes = "use less verbose output")
  val antlr4Trace: ScallopOption[Boolean] =
    toggle(descrYes = "enable trace logging of the ANTLR4 parser")

  val check = new ParserSubcommand("check", "Type check WDL file.")
  addSubcommand(check)

  val format =
    new ParserSubcommand("format",
                         "Reformat WDL file and all its dependencies according to style rules.") {
      val wdlVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
          descr = "WDL version to generate; currently only v1.0 is supported",
          default = Some(WdlVersion.V1_0)
      )
      validateOpt(wdlVersion) {
        case Some(version) if version != WdlVersion.V1_0 =>
          Left("Only WDL v1.0 is supported currently")
        case _ => Right(Unit)
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
    }
  addSubcommand(format)

  val upgrade = new ParserSubcommand("upgrade", "Upgrade a WDL file to a more recent version") {
    val srcVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version of the document being upgraded",
        default = None
    )
    val destVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version of the document being upgraded",
        default = Some(WdlVersion.V1_0)
    )
    validateOpt(destVersion) {
      case Some(version) if version != WdlVersion.V1_0 =>
        Left("Only WDL v1.0 is supported currently")
      case _ => Right(Unit)
    }
    validateOpt(srcVersion, destVersion) {
      case (Some(src), Some(dst)) if src < dst => Right(Unit)
      // ignore if srcVersion is unspecified - it will be detected and validated in the command
      case (None, _) => Right(Unit)
      case _         => Left("Source version must be earlier than destination version")
    }
    val outputDir: ScallopOption[Path] = opt[Path](descr =
      "Directory in which to output upgraded WDL file(s); if not specified, the input files are overwritten"
    )
    val overwrite: ScallopOption[Boolean] = toggle(default = Some(false))
    validateOpt(outputDir, overwrite) {
      case (None, Some(false) | None) =>
        Left("--outputDir is required unless --overwrite is specified")
      case _ => Right(Unit)
    }
  }
  addSubcommand(upgrade)

  val printAST =
    new ParserSubcommand("printAST", "Print the Abstract Syntax Tree for a WDL file.")
  addSubcommand(printAST)

  val readmes =
    new ParserSubcommand("readmes", "Generate README file stubs for tasks and workflows.") {
      val developerReadmes: ScallopOption[Boolean] = toggle(
          descrYes = "also generate developer READMEs",
          default = Some(false)
      )
      val outputDir: ScallopOption[Path] = opt[Path](descr =
        "Directory in which to output formatted WDL files; if not specified, the input files are overwritten"
      )
      val overwrite: ScallopOption[Boolean] = toggle(default = Some(false))
    }
  addSubcommand(readmes)

  verify()

  /**
    * The verbosity level
    * @return
    */
  def verbosity: Verbosity = {
    if (this.verbose.getOrElse(default = false)) {
      Verbose
    } else if (this.quiet.getOrElse(default = false)) {
      Quiet
    } else {
      Normal
    }
  }
}
