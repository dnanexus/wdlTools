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
import wdlTools.syntax.WdlVersion
import wdlTools.types.{TypeCheckingRegime, TypeOptions}
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.util.Util.{FILE_SCHEME, getUriScheme}
import wdlTools.util.{BasicOptions, FileSourceResolver, Logger, Options, TraceLevel}

import scala.util.Try

/**
  * Base class for wdlTools CLI commands.
  */
trait Command {
  def apply(): Unit
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

  abstract class WdlToolsSubcommand(name: String, description: String) extends Subcommand(name) {
    // there is a compiler bug that prevents accessing name directly
    banner(s"""Usage: wdlTools ${commandNameAndAliases.head} [OPTIONS] <path|uri>
              |${description}
              |
              |Options:
              |""".stripMargin)

    val verbose: ScallopOption[Boolean] = opt(descr = "use more verbose output")
    val quiet: ScallopOption[Boolean] = opt(descr = "use less verbose output")

    /**
      * The verbosity level
      * @return
      */
    def logger: Logger = {
      val quiet = this.quiet.getOrElse(default = false)
      val verbose = this.verbose.getOrElse(default = false)
      Logger(quiet, if (verbose) TraceLevel.Verbose else TraceLevel.None)
    }

    def getOptions: Options
  }

  private def getParent(pathOrUri: String): Path = {
    (getUriScheme(pathOrUri) match {
      case Some(FILE_SCHEME) => Paths.get(URI.create(pathOrUri))
      case None              => Paths.get(pathOrUri)
      case _                 => throw new Exception(s"${pathOrUri} is not a path or file:// URI")
    }).getParent
  }

  trait ParserOptions {
    def antlr4Trace: ScallopOption[Boolean]
    def localDir: ScallopOption[List[Path]]

    /**
      * The local directories to search for WDL imports.
      * @param pathOrUri a file path or URI, from which to get the parent directory to add to the paths
      * @return
      */
    def localDirectories(pathOrUri: String): Vector[Path] = {
      val path = Set(getParent(pathOrUri))
      if (this.localDir.isDefined) {
        (this.localDir().toSet ++ path).toVector
      } else {
        path
      }.toVector
    }
  }

  abstract class ParserSubcommand(name: String, description: String)
      extends WdlToolsSubcommand(name: String, description: String)
      with ParserOptions {
    val antlr4Trace: ScallopOption[Boolean] = opt(
        descr = "enable trace logging of the ANTLR4 parser"
    )
    val localDir: ScallopOption[List[Path]] =
      opt[List[Path]](
          descr =
            "directory in which to search for imports; ignored if --nofollow-imports is specified"
      )
    val followImports: ScallopOption[Boolean] = toggle(
        descrYes = "(Default) format imported files in addition to the main file",
        descrNo = "only format the main file",
        default = Some(true)
    )
    val uri: ScallopOption[String] =
      trailArg[String](descr = "path or String (file:// or http(s)://) to the main WDL file")

    def getOptions: Options = {
      val localDirs = this.localDirectories(uri())
      BasicOptions(
          fileResolver = FileSourceResolver.create(localDirs, logger = logger),
          followImports = followImports(),
          logger = logger,
          antlr4Trace = antlr4Trace()
      )
    }
  }

  version(s"wdlTools ${wdlTools.getVersion}")
  banner("""Usage: wdlTools <COMMAND> [OPTIONS]
           |Options:
           |""".stripMargin)

  val check = new WdlToolsSubcommand(
      name = "check",
      description = "Type check WDL file."
  ) with ParserOptions {
    val antlr4Trace: ScallopOption[Boolean] =
      opt(descr = "enable trace logging of the ANTLR4 parser")
    val localDir: ScallopOption[List[Path]] =
      opt[List[Path]](
          descr =
            "directory in which to search for imports; ignored if --nofollow-imports is specified"
      )
    val uri: ScallopOption[String] =
      trailArg[String](descr = "path or String (file:// or http(s)://) to the main WDL file")

    val regime: ScallopOption[TypeCheckingRegime] = opt[TypeCheckingRegime](
        descr = "Strictness of type checking",
        default = Some(TypeCheckingRegime.Moderate)
    )
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

    def getOptions: TypeOptions = {
      val localDirs = this.localDirectories(uri())
      TypeOptions(
          FileSourceResolver.create(localDirs, logger = logger),
          logger = logger,
          antlr4Trace = antlr4Trace(),
          typeChecking = regime()
      )
    }
  }
  addSubcommand(check)

  val docgen = new ParserSubcommand(
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

  val format = new ParserSubcommand(
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

  val lint = new ParserSubcommand(
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

    override def getOptions: TypeOptions = {
      val localDirs = this.localDirectories(uri())
      TypeOptions(
          fileResolver = FileSourceResolver.create(localDirs, logger = logger),
          followImports = followImports(),
          logger = logger,
          antlr4Trace = antlr4Trace(),
          typeChecking = TypeCheckingRegime.Strict
      )
    }
  }
  addSubcommand(lint)

  val upgrade = new ParserSubcommand(
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

    def getOptions: Options = {
      val localDirs = Vector(getParent(uri()))
      BasicOptions(
          fileResolver = FileSourceResolver.create(localDirs, logger = logger),
          followImports = typed(),
          logger = logger,
          antlr4Trace = antlr4Trace()
      )
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

    def getOptions: Options = {
      BasicOptions(fileResolver = FileSourceResolver.create(logger = logger), logger = logger)
    }
  }
  addSubcommand(generate)

  val readmes = new ParserSubcommand(
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
