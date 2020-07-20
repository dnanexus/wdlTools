package wdlTools.generators.code

import wdlTools.syntax.{Parsers, WdlVersion}
import wdlTools.util.{FileSource, FileSourceResolver, Logger}

case class Upgrader(followImports: Boolean = false,
                    fileResolver: FileSourceResolver = FileSourceResolver.get,
                    logger: Logger = Logger.get) {
  private val parsers = Parsers(followImports, fileResolver, logger = logger)

  def upgrade(docSource: FileSource,
              srcVersion: Option[WdlVersion] = None,
              destVersion: WdlVersion = WdlVersion.V1): Map[FileSource, Seq[String]] = {
    val parser = if (srcVersion.isDefined) {
      parsers.getParser(srcVersion.get)
    } else {
      parsers.getParser(docSource)
    }

    // the parser will follow imports, so the formatter should not
    val formatter = WdlV1Formatter(followImports)

    // parse and format the document (and any imports)
    parser.Walker[Map[FileSource, Seq[String]]](docSource, Map.empty).walk { (doc, results) =>
      if (doc.version.value >= destVersion) {
        throw new Exception(s"Cannot convert WDL version ${doc.version} to ${destVersion}")
      }
      results + (doc.source -> formatter.formatDocument(doc))
    }
  }
}
