package wdlTools.cli

import java.nio.file.{Path, Paths}
import wdlTools.generators.code.WdlGenerator
import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, FileUtils, LinesFileNode}
import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.TypedAbstractSyntax.{Document, ImportDoc}
import wdlTools.types.{TypeCheckingRegime, TypeException, TypeInfer}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.collection.immutable.SeqMap
import scala.language.reflectiveCalls

case class Fix(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val fileResolver = FileSourceResolver.get
    val docSource = fileResolver.resolve(conf.fix.uri())
    val baseFileSource =
      conf.fix.baseUri.toOption
        .map(fileResolver.resolveDirectory(_))
        .orElse(docSource.getParent)
        .getOrElse(
            throw new Exception(s"cannot determine the base FileSource for main WDL ${docSource}")
        )
    val srcVersion = conf.fix.srcVersion.toOption
    val outputDir = conf.fix.outputDir.toOption.getOrElse(Paths.get("."))
    val overwrite = conf.fix.overwrite()
    val wdlVersion = srcVersion.getOrElse(Parsers.default.getWdlVersion(docSource))

    def parseAndCheck(
        source: FileNode,
        message: String,
        regime: TypeCheckingRegime.TypeCheckingRegime,
        fileResolver: FileSourceResolver
    ): Document = {
      val errorHandler = new ErrorHandler()
      val parsers = Parsers(conf.fix.followImports(), fileResolver = fileResolver)
      val parser = parsers.getParser(wdlVersion)
      val checker =
        TypeInfer(regime, fileResolver = fileResolver, errorHandler = Some(errorHandler.apply))
      val document =
        try {
          val (document, _) = checker.apply(parser.parseDocument(source))
          document
        } catch {
          case e: SyntaxException =>
            throw new Exception(s"Failed to parse ${source}; ${message}", e)
          case e: TypeException =>
            throw new Exception(s"Failed to type-check ${source}; ${message}", e)
        }
      if (errorHandler.hasErrors) {
        val msgStream = new ByteArrayOutputStream()
        errorHandler.printErrors(new PrintStream(msgStream), effects = true)
        throw new Exception(
            s"Failed to type-check ${source}; ${message}:\n${msgStream.toString()}"
        )
      }
      document
    }

    val document = parseAndCheck(docSource,
                                 "this error cannot be fixed automatically",
                                 TypeCheckingRegime.Lenient,
                                 fileResolver)

    // use a SeqMap so that the first entry will be the file generated from the source document
    val generator =
      WdlGenerator(Some(wdlVersion), omitNullCallInputs = false, rewriteNonstandardUsages = true)
    var results: SeqMap[Path, Iterable[String]] = SeqMap.empty
    var visited: Set[String] = Set.empty

    def generateDocument(fileSource: FileNode, doc: Document): Unit = {
      fileSource match {
        case fs: AddressableFileSource if !visited.contains(fileSource.toString) =>
          visited += fileSource.toString
          val outputPath = outputDir.resolve(baseFileSource.relativize(fs))
          val lines = generator.generateDocument(doc)
          results += outputPath -> lines
          doc.elements.foreach {
            case ImportDoc(_, _, addr, doc) =>
              generateDocument(fileResolver.resolve(addr, Some(fs)), doc)
            case _ => ()
          }
        case _ => ()
      }
    }

    // recursively generate documents with correct syntax from the ASTs
    generateDocument(docSource, document)

    // validate that the generated files parse
    val validationParser = Parsers.default.getParser(wdlVersion)
    results.foreach {
      case (path, lines) =>
        try {
          validationParser.parseDocument(LinesFileNode(lines.toVector, path.toString))
        } catch {
          case e: SyntaxException =>
            throw new Exception(
                s"Generated code is syntactically invalid:\n${lines.mkString(System.lineSeparator())}",
                e
            )
        }
    }

    // write out fixed versions
    results.foreach {
      case (path, lines) =>
        FileUtils.writeFileContent(path, lines.mkString(System.lineSeparator()), overwrite)
    }

    // Validate that the fixed versions type-check correctly - we can't do this until
    // after we write out the documents because otherwise import resolution won't work -
    // this should only fail if there is a bug in the code generator. Prepend the output
    // dir to the search path.
    val rewrittenFileResolver = fileResolver.addToLocalSearchPath(Vector(outputDir), append = false)
    parseAndCheck(
        rewrittenFileResolver.fromPath(results.head._1),
        "this is likely due to a bug in the WDL code generator - please report this issue",
        TypeCheckingRegime.Moderate,
        rewrittenFileResolver
    )
  }
}
