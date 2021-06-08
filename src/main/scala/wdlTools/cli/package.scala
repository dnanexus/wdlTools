package wdlTools

import dx.util.{
  AddressableFileSource,
  FileNode,
  FileSource,
  FileSourceResolver,
  FileUtils,
  LocalFileSource
}
import wdlTools.syntax.{Parsers, SyntaxException, WdlVersion}
import wdlTools.types.{TypeCheckingRegime, TypeException, TypeInfer}
import wdlTools.types.TypedAbstractSyntax.Document

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Path, Paths}
import scala.collection.immutable.SeqMap
import scala.jdk.CollectionConverters._

package object cli {

  def parseAndCheck(source: FileNode,
                    wdlVersion: WdlVersion,
                    followImports: Boolean = true,
                    message: String,
                    regime: TypeCheckingRegime.TypeCheckingRegime = TypeCheckingRegime.Moderate,
                    fileResolver: FileSourceResolver = FileSourceResolver.get): Document = {
    val errorHandler = new ErrorHandler()
    val parsers = Parsers(followImports, fileResolver = fileResolver)
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

  def writeDocuments[T <: FileSource](
      docs: SeqMap[T, Iterable[String]],
      outputDir: Option[Path] = None,
      overwrite: Boolean = false
  ): Map[AddressableFileSource, Path] = {
    val writableDocs = docs.flatMap {
      case (fs: AddressableFileSource, lines) if outputDir.isDefined => Some(fs -> lines)
      case (local: LocalFileSource, lines) if overwrite =>
        FileUtils.writeFileContent(local.canonicalPath,
                                   lines.mkString(System.lineSeparator()),
                                   overwrite = true)
        None
      case (fs, lines) =>
        println(s"${fs.toString}\n${lines.mkString(System.lineSeparator())}")
        None
    }

    if (writableDocs.nonEmpty) {
      // determine the common ancestor path between all generated files
      val rootPath = Paths.get("/")
      val sourceToRelPath = writableDocs.keys.map { fs =>
        fs -> rootPath.relativize(Paths.get(fs.folder).resolve(fs.name))
      }.toMap
      val pathComponents = sourceToRelPath.values.map(_.iterator().asScala.toVector)
      // don't include the file name in the path components
      val shortestPathSize = pathComponents.map(_.size - 1).min
      val commonPathComponents = pathComponents
        .map(_.slice(0, shortestPathSize))
        .transpose
        .iterator
        .map(_.toSet)
        .takeWhile(_.size == 1)
        .map(_.head.toString)
        .toVector
      val commonAncestor = Paths.get(commonPathComponents.head, commonPathComponents.tail: _*)
      writableDocs.map {
        case (fs, lines) =>
          val outputPath = outputDir.get.resolve(commonAncestor.relativize(sourceToRelPath(fs)))
          FileUtils.writeFileContent(outputPath,
                                     lines.mkString(System.lineSeparator()),
                                     overwrite = overwrite)
          fs -> outputPath
      }
    } else {
      Map.empty
    }
  }
}
