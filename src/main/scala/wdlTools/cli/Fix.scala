package wdlTools.cli

import java.nio.file.{Path, Paths}
import wdlTools.generators.code.WdlGenerator
import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, FileUtils}
import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.TypedAbstractSyntax.{Document, ImportDoc}
import wdlTools.types.{TypeCheckingRegime, TypeException, TypeInfer}

import scala.language.reflectiveCalls

case class Fix(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val fileResolver = FileSourceResolver.get
    val docSource = fileResolver.resolve(conf.fix.uri())
    val srcVersion = conf.fix.srcVersion.toOption
    val outputDir = conf.upgrade.outputDir.toOption.getOrElse(Paths.get("."))
    val overwrite = conf.upgrade.overwrite()
    val wdlVersion = srcVersion.getOrElse(Parsers.default.getWdlVersion(docSource))

    val parsers = Parsers(conf.upgrade.followImports())
    val parser = parsers.getParser(wdlVersion)
    val errorHandler = new ErrorHandler()
    val checker = TypeInfer(TypeCheckingRegime.Lenient, errorHandler = Some(errorHandler.apply))
    val baseFileSource =
      conf.fix.baseUri.toOption
        .map(fileResolver.resolveDirectory)
        .orElse(docSource.getParent)
        .getOrElse(
            throw new Exception(s"cannot determine the base FileSource for main WDL ${docSource}")
        )

    try {
      val generator = WdlGenerator(Some(wdlVersion), omitNullCallInputs = false)
      var visited: Set[String] = Set.empty
      var results: Map[Path, Iterable[String]] = Map.empty

      def generateDocument(fileSource: FileNode, doc: Document): Unit = {
        fileSource match {
          case fs: AddressableFileSource if !visited.contains(fileSource.toString) =>
            visited += fileSource.toString
            val outputPath = outputDir.resolve(baseFileSource.relativize(fs))
            val lines = generator.generateDocument(doc)
            results += outputPath -> lines
            doc.elements.foreach {
              case ImportDoc(_, _, addr, doc) =>
                generateDocument(fileResolver.resolve(addr), doc)
              case _ => ()
            }
        }
      }

      val (document, _) = checker.apply(parser.parseDocument(docSource))
      generateDocument(docSource, document)

      // write out upgraded versions
      results.foreach {
        case (path, lines) =>
          FileUtils.writeFileContent(path, lines.mkString(System.lineSeparator()), overwrite)
      }
    } catch {
      case e: SyntaxException =>
        println(
            s"Failed to parse WDL document; this error cannot be fixed automatically: ${e.getMessage}"
        )
      case e: TypeException =>
        println(
            s"Failed to type-check WDL document; this error cannot be fixed automatically: ${e.getMessage}"
        )
    }
  }
}
