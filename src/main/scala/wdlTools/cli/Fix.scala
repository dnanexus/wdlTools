package wdlTools.cli

import java.nio.file.Paths
import wdlTools.generators.code.WdlGenerator
import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, LinesFileNode}
import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.TypedAbstractSyntax.{Document, ImportDoc}
import wdlTools.types.TypeCheckingRegime

import scala.collection.immutable.SeqMap
import scala.language.reflectiveCalls

case class Fix(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val fileResolver = FileSourceResolver.get
    val docSource = fileResolver.resolve(conf.fix.uri())
    val srcVersion = conf.fix.srcVersion.toOption
    val wdlVersion = srcVersion.getOrElse(Parsers.default.getWdlVersion(docSource))

    val document = parseAndCheck(docSource,
                                 wdlVersion,
                                 conf.fix.followImports(),
                                 "this error cannot be fixed automatically",
                                 TypeCheckingRegime.Lenient,
                                 fileResolver)

    // use a SeqMap so that the first entry will be the file generated from the source document
    val generator =
      WdlGenerator(Some(wdlVersion), omitNullCallInputs = false, rewriteNonstandardUsages = true)
    var results: SeqMap[AddressableFileSource, Iterable[String]] = SeqMap.empty

    def generateDocument(fileSource: FileNode, doc: Document): Unit = {
      fileSource match {
        case fs: AddressableFileSource if !results.contains(fs) =>
          val lines = generator.generateDocument(doc)
          results += fs -> lines
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
      case (fs, lines) =>
        try {
          validationParser.parseDocument(LinesFileNode(lines.toVector, fs.toString))
        } catch {
          case e: SyntaxException =>
            throw new Exception(
                s"Generated code is syntactically invalid:\n${lines.mkString(System.lineSeparator())}",
                e
            )
        }
    }

    // Validate that the fixed versions type-check correctly - we can't do this until
    // after we write out the documents because otherwise import resolution won't work -
    // this should only fail if there is a bug in the code generator.
    val outputDir = conf.fix.outputDir.toOption.getOrElse(Paths.get("."))
    val overwrite = conf.fix.overwrite()
    val outputPaths = writeDocuments(results, Some(outputDir), overwrite)
    // Prepend the output dir to the search path.
    val rewrittenFileResolver = fileResolver.addToLocalSearchPath(Vector(outputDir), append = false)
    parseAndCheck(
        rewrittenFileResolver.fromPath(outputPaths(results.head._1)),
        wdlVersion,
        conf.fix.followImports(),
        "this may be an unsupported fix, or it may be due to a bug in the WDL code generator - please report this issue",
        TypeCheckingRegime.Moderate,
        rewrittenFileResolver
    )
  }
}
