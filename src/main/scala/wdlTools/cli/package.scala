package wdlTools.cli

import java.net.URI
import java.nio.file.{Path, Paths}

import org.rogach.scallop.{
  ArgType,
  ScallopConf,
  ScallopOption,
  Subcommand,
  ValueConverter,
  singleArgConverter
}
import wdlTools.syntax.{Antlr4Util, WdlVersion}
import wdlTools.types.TypeCheckingRegime
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.util.FileUtils.{FILE_SCHEME, getUriScheme}
import wdlTools.util.{FileSourceResolver, Logger}

import scala.util.Try

/**
  * Base class for wdlTools CLI commands.
  */
trait Command {
  def apply(): Unit
}

abstract class InitializableSubcommand(name: String) extends Subcommand(name) {
  def init(): Unit
}

class WdlToolsConf(args: Seq[String]) extends ScallopConf(args) {
  def exceptionHandler[T]: PartialFunction[Throwable, Either[String, Option[T]]] = {
    case t: Throwable => Left(s"${t.getMessage}")
  }
  def listArgConverter[A](
      conv: String => A,
      handler: PartialFunction[Throwable, Either[String, Option[List[A]]]] = PartialFunction.empty
  ): ValueConverter[List[A]] = new ValueConverter[List[A]] {
    def parse(s: List[(String, List[String])]): Either[String, Option[List[A]]] = {
      Try({
        val l = s.flatMap(_._2).map(i => conv(i))
        if (l.isEmpty) {
          Right(None)
        } else {
          Right(Some(l))
        }
      }).recover(handler)
        .recover({
          case _: Exception => Left("wrong arguments format")
        })
        .get
    }
    val argType = ArgType.LIST
  }
  implicit val fileListConverter: ValueConverter[List[Path]] =
    listArgConverter[Path](Paths.get(_), exceptionHandler[List[Path]])
  implicit val versionConverter: ValueConverter[WdlVersion] =
    singleArgConverter[WdlVersion](WdlVersion.withName, exceptionHandler[WdlVersion])
  implicit val tcRegimeConverter: ValueConverter[TypeCheckingRegime] =
    singleArgConverter[TypeCheckingRegime](TypeCheckingRegime.withName,
                                           exceptionHandler[TypeCheckingRegime])

  abstract class WdlToolsSubcommand(name: String, description: String)
      extends InitializableSubcommand(name) {
    // there is a compiler bug that prevents accessing name directly
    banner(s"""Usage: wdlTools ${commandNameAndAliases.head} [OPTIONS] <path|uri>
              |${description}
              |
              |Options:
              |""".stripMargin)

    val verbose: ScallopOption[Int] = tally(descr = "use more verbose output")
    val quiet: ScallopOption[Boolean] = opt(descr = "use less verbose output")

    /**
      * The verbosity level
      * @return
      */
    def init(): Unit = {
      val quiet = this.quiet.getOrElse(default = false)
      val traceLevel = this.verbose.getOrElse(default = 0)
      Logger.set(quiet, traceLevel)
    }
  }

  private def getParent(pathOrUri: String): Path = {
    (getUriScheme(pathOrUri) match {
      case Some(FILE_SCHEME) => Paths.get(URI.create(pathOrUri))
      case None              => Paths.get(pathOrUri)
      case _                 => throw new Exception(s"${pathOrUri} is not a path or file:// URI")
    }).getParent
  }

  /**
    * The local directories to search for WDL imports.
    * @param pathOrUri a file path or URI, from which to get the parent directory to add to the paths
    * @return
    */
  private def localDirectories(pathOrUri: String,
                               localDirs: ScallopOption[List[Path]]): Vector[Path] = {
    localDirs.toOption match {
      case None        => Vector(getParent(pathOrUri))
      case Some(paths) => (paths.toSet ++ Set(getParent(pathOrUri))).toVector
    }
  }

  class ParserSubcommand(name: String, description: String)
      extends WdlToolsSubcommand(name: String, description: String) {
    val antlr4Trace: ScallopOption[Boolean] = opt(
        descr = "enable trace logging of the ANTLR4 parser"
    )
    val localDir: ScallopOption[List[Path]] =
      opt[List[Path]](
          descr =
            "directory in which to search for imports; ignored if --nofollow-imports is specified"
      )
    val uri: ScallopOption[String] =
      trailArg[String](descr = "path or String (file:// or http(s)://) to the main WDL file")

    override def init(): Unit = {
      super.init()
      FileSourceResolver.set(localDirectories(uri(), localDir))
      Antlr4Util.setParserTrace(antlr4Trace())
    }
  }

  version(s"wdlTools ${wdlTools.getVersion}")
  banner("""Usage: wdlTools <COMMAND> [OPTIONS]
           |Options:
           |""".stripMargin)

  class ParserCheckerSubcommand(name: String, description: String)
      extends ParserSubcommand(name, description) {
    val regime: ScallopOption[TypeCheckingRegime] = opt[TypeCheckingRegime](
        descr = "Strictness of type checking",
        default = Some(TypeCheckingRegime.Moderate)
    )
  }

  val check = new ParserCheckerSubcommand(
      name = "check",
      description = "Type check WDL file."
  ) {
    val json: ScallopOption[Boolean] = toggle(
        descrYes = "Output type-check errors as JSON",
        descrNo = "Output type-check errors as plain text",
        default = Some(false)
    )
    val outputFile: ScallopOption[Path] = opt[Path](
        descr =
          "File in which to write type-check errors; if not specified, errors are written to stdout"
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(check)

  class FollowOptionalParserSubcommand(name: String, description: String)
      extends ParserSubcommand(name, description) {
    val followImports: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) format imported files in addition to the main file",
        descrNo = "only format the main file",
        default = Some(true)
    )
  }

  val docgen = new FollowOptionalParserSubcommand(
      name = "docgen",
      description = "Generate documentation from a WDL file and all its dependencies"
  ) {
    val title: ScallopOption[String] = opt[String](
        descr = "Title for generated documentation"
    )
    val outputDir: ScallopOption[Path] = opt[Path](
        descr = "Directory in which to output documentation",
        short = 'O',
        default = Some(Paths.get("docs"))
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(docgen)

  val format = new FollowOptionalParserSubcommand(
      name = "format",
      description = "Reformat WDL file and all its dependencies according to style rules."
  ) {
    val wdlVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version to generate; currently only v1.0 is supported",
        default = Some(WdlVersion.V1)
    )
    validateOpt(wdlVersion) {
      case Some(version) if version != WdlVersion.V1 =>
        Left("Only WDL v1.0 is supported currently")
      case _ => Right(())
    }
    val outputDir: ScallopOption[Path] = opt[Path](
        descr = "Directory in which to output formatted WDL files",
        short = 'O'
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(format)

  val lint = new ParserCheckerSubcommand(
      name = "lint",
      description = "Check WDL file for common mistakes and bad code smells"
  ) {
    val config: ScallopOption[Path] = opt[Path](descr = "Path to lint config file")
    val includeRules: ScallopOption[List[String]] = opt[List[String]](
        descr =
          "Lint rules to include; may be of the form '<rule>=<level>' to change the default level"
    )
    val excludeRules: ScallopOption[List[String]] =
      opt[List[String]](descr = "Lint rules to exclude")
    validateOpt(config, includeRules, excludeRules) {
      case _ if config.isDefined && (includeRules.isDefined || excludeRules.isDefined) =>
        Left("--config and --include-rules/--exclude-rules are mutually exclusive")
      case _ => Right(())
    }
    val json: ScallopOption[Boolean] = toggle(
        descrYes = "Output lint errors as JSON",
        descrNo = "Output lint errors as plain text",
        default = Some(false)
    )
    val outputFile: ScallopOption[Path] = opt[Path](
        descr = "File in which to write lint errors; if not specified, errors are written to stdout"
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(lint)

  val upgrade = new FollowOptionalParserSubcommand(
      name = "upgrade",
      description = "Upgrade a WDL file to a more recent version"
  ) {
    val srcVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version of the document being upgraded",
        default = None
    )
    val destVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version of the document being upgraded",
        default = Some(WdlVersion.V1)
    )
    validateOpt(destVersion) {
      case Some(version) if version != WdlVersion.V1 =>
        Left("Only WDL v1.0 is supported currently")
      case _ => Right(())
    }
    validateOpt(srcVersion, destVersion) {
      case (Some(src), Some(dst)) if src < dst => Right(())
      // ignore if srcVersion is unspecified - it will be detected and validated in the command
      case (None, _) => Right(())
      case _         => Left("Source version must be earlier than destination version")
    }
    val outputDir: ScallopOption[Path] = opt[Path](
        descr = "Directory in which to output upgraded WDL file(s)",
        short = 'O'
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(upgrade)

  val exec = new WdlToolsSubcommand(
      name = "exec",
      description = "Execute a WDL task"
  ) {
    val task: ScallopOption[String] = opt(
        descr = "The task name - requred unless the WDL has a single task"
    )
    val inputsFile: ScallopOption[Path] = opt(
        name = "inputs",
        descr = "Task inputs JSON file - if not specified, inputs are read from stdin"
    )
    val runtimeDefaults: ScallopOption[Path] = opt(
        descr = "JSON file with default runtime values"
    )
    val outputsFile: ScallopOption[Path] = opt(
        name = "outputs",
        descr = "Task outputs JSON file - if not specified, outputs are written to stdout"
    )
    val summaryFile: ScallopOption[Path] = opt(
        name = "summary",
        descr = "Also write a job summary in JSON format"
    )
    val container: ScallopOption[Boolean] = toggle(
        descrYes = "Execute the task using Docker (if applicable)",
        descrNo = "Do not use Docker - all dependencies must be installed on the local system",
        default = Some(true)
    )
    val localDir: ScallopOption[List[Path]] =
      opt[List[Path]](
          descr =
            "directory in which to search for imports; ignored if --nofollow-imports is specified"
      )
    val uri: ScallopOption[String] =
      trailArg[String](descr = "path or String (file:// or http(s)://) to the main WDL file")
  }
  addSubcommand(exec)

  val printTree = new WdlToolsSubcommand(
      name = "printTree",
      description = "Print the Abstract Syntax Tree for a WDL file."
  ) {
    val antlr4Trace: ScallopOption[Boolean] = opt(
        descr = "enable trace logging of the ANTLR4 parser"
    )
    val uri: ScallopOption[String] =
      trailArg[String](descr = "path or String (file:// or http(s)://) to the main WDL file")

    val typed: ScallopOption[Boolean] = toggle(
        descrYes = "Print the typed AST (document must pass type-checking)",
        descrNo = "Print the raw AST",
        default = Some(false)
    )
    val regime: ScallopOption[TypeCheckingRegime] = opt[TypeCheckingRegime](
        descr = "Strictness of type checking",
        default = Some(TypeCheckingRegime.Moderate)
    )

    override def init(): Unit = {
      Antlr4Util.setParserTrace(antlr4Trace())
    }
  }
  addSubcommand(printTree)

  val generate = new WdlToolsSubcommand(
      name = "new",
      description = "Generate a new WDL task, workflow, or project."
  ) {
    val wdlVersion: ScallopOption[WdlVersion] = opt[WdlVersion](
        descr = "WDL version to generate; currently only v1.0 is supported",
        default = Some(WdlVersion.V1)
    )
    validateOpt(wdlVersion) {
      case Some(version) if version != WdlVersion.V1 =>
        Left("Only WDL v1.0 is supported currently")
      case _ => Right(())
    }
    val interactive: ScallopOption[Boolean] = toggle(
        descrYes = "Specify inputs and outputs interactively",
        descrNo = "(Default) Do not specify inputs and outputs interactively",
        default = Some(false)
    )
    val workflow: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) Generate a workflow",
        descrNo = "Do not generate a workflow",
        default = Some(true)
    )
    val task: ScallopOption[List[String]] = opt[List[String]](descr = "A task name")
    val readmes: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) Generate a README file for each task/workflow",
        descrNo = "Do not generate README files",
        default = Some(true)
    )
    val docker: ScallopOption[String] = opt[String](descr = "The Docker image ID")
    val dockerfile: ScallopOption[Boolean] = toggle(
        descrYes = "Generate a Dockerfile template",
        descrNo = "(Default) Do not generate a Dockerfile template",
        default = Some(false)
    )
    val tests: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) Generate pytest-wdl test template",
        descrNo = "Do not generate a pytest-wdl test template",
        default = Some(true)
    )
    val makefile: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) Generate a Makefile",
        descrNo = "Do not generate a Makefile",
        default = Some(true)
    )
    val outputDir: ScallopOption[Path] = opt[Path](
        descr =
          "Directory in which to output formatted WDL files; if not specified, ./<name> is used",
        short = 'O'
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
    val name: ScallopOption[String] =
      trailArg[String](descr = "The project name - this will also be the name of the workflow")
  }
  addSubcommand(generate)

  val readmes = new FollowOptionalParserSubcommand(
      name = "readmes",
      description = "Generate README file stubs for tasks and workflows."
  ) {
    val developerReadmes: ScallopOption[Boolean] = toggle(
        descrYes = "also generate developer READMEs",
        default = Some(false)
    )
    val outputDir: ScallopOption[Path] = opt[Path](
        descr =
          "Directory in which to output README files; if not specified, the current directory is used",
        short = 'O'
    )
    val overwrite: ScallopOption[Boolean] = toggle(
        descrYes = "Overwrite existing files",
        descrNo = "(Default) Do not overwrite existing files",
        default = Some(false)
    )
  }
  addSubcommand(readmes)

  verify()
}
