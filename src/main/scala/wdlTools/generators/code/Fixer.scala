package wdlTools.generators.code

import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, LinesFileNode}
import wdlTools.syntax.{Parsers, SyntaxException, WdlParser, WdlVersion}
import wdlTools.types.{
  TypeCheckingRegime,
  TypeErrorHandler,
  TypeException,
  TypeInfer,
  TypedAbstractSyntax => TAT
}

import java.nio.file.Path
import scala.collection.immutable.SeqMap

case class Fixer(followImports: Boolean = true,
                 fileResolver: FileSourceResolver = FileSourceResolver.get) {
  private lazy val parsers = Parsers(followImports, fileResolver = fileResolver)

  private def check(source: FileNode,
                    parser: WdlParser,
                    regime: TypeCheckingRegime.TypeCheckingRegime,
                    message: String,
                    fileResolver: FileSourceResolver,
                    errorHandler: Option[TypeErrorHandler]): TAT.Document = {
    val checker = TypeInfer(regime, fileResolver = fileResolver, errorHandler = errorHandler)
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

    errorHandler.foreach { eh =>
      if (eh.hasTypeErrors) {
        throw new Exception(
            s"Failed to type-check ${source}; ${message}:\n${errorHandler.toString}"
        )
      }
    }

    document
  }

  def fix(docSource: FileNode,
          outputDir: Path,
          overwrite: Boolean = true,
          sourceVersion: Option[WdlVersion] = None,
          errorHandler: Option[TypeErrorHandler] = None): Unit = {
    val wdlVersion = sourceVersion.getOrElse(parsers.getWdlVersion(docSource))
    val parser = parsers.getParser(wdlVersion)
    val document = check(docSource,
                         parser,
                         TypeCheckingRegime.Lenient,
                         "this error cannot be fixed automatically",
                         fileResolver,
                         errorHandler)

    // use a SeqMap so that the first entry will be the file generated from the source document
    val generator =
      WdlGenerator(Some(wdlVersion), omitNullCallInputs = false, rewriteNonstandardUsages = true)
    var results: SeqMap[AddressableFileSource, Iterable[String]] = SeqMap.empty

    def generateDocument(fileSource: FileNode, doc: TAT.Document): Unit = {
      fileSource match {
        case fs: AddressableFileSource if !results.contains(fs) =>
          val lines = generator.generateDocument(doc)
          results += fs -> lines
          doc.elements.foreach {
            case TAT.ImportDoc(_, _, addr, doc) =>
              generateDocument(fileResolver.resolve(addr, Some(fs)), doc)
            case _ => ()
          }
        case _ => ()
      }
    }

    // recursively generate documents with correct syntax from the ASTs
    generateDocument(docSource, document)

    // validate that the generated files parse
    results.foreach {
      case (fs, lines) =>
        try {
          parser.parseDocument(LinesFileNode(lines.toVector, fs.toString))
        } catch {
          case e: SyntaxException =>
            throw new Exception(
                s"Generated code is syntactically invalid:\n${lines.mkString(System.lineSeparator())}",
                e
            )
        }
    }

    val outputPaths = Utils.writeDocuments(results, Some(outputDir), overwrite)

    // Validate that the fixed versions type-check correctly - we can't do this until
    // after we write out the documents because otherwise import resolution won't work -
    // this should only fail if there is a bug in the code generator.
    // Prepend the output dir to the search path.
    val fixedFileResolver = fileResolver.addToLocalSearchPath(Vector(outputDir), append = false)
    val fixedSource = fixedFileResolver.fromPath(outputPaths(results.head._1))
    check(
        fixedSource,
        parser,
        TypeCheckingRegime.Moderate,
        """this may be an unsupported fix, or it may be due to a bug in the WDL code generator - 
          |please report this issue""".stripMargin.replaceAll("\n", " "),
        fixedFileResolver,
        errorHandler
    )
  }
}
