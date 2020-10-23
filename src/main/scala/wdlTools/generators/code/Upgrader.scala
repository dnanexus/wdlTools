package wdlTools.generators.code

import wdlTools.syntax.{Parsers, WdlVersion}
import dx.util.{FileNode, FileSourceResolver, Logger}

case class Upgrader(followImports: Boolean = false,
                    fileResolver: FileSourceResolver = FileSourceResolver.get,
                    logger: Logger = Logger.get) {
  private val parsers = Parsers(followImports, fileResolver, logger = logger)

  def upgrade(docSource: FileNode,
              sourceVersion: Option[WdlVersion] = None,
              targetVersion: WdlVersion = WdlVersion.V1): Map[FileNode, Seq[String]] = {
    val parser = if (sourceVersion.isDefined) {
      parsers.getParser(sourceVersion.get)
    } else {
      parsers.getParser(docSource)
    }

    // the parser will follow imports, so the formatter should not
    val formatter = WdlFormatter(Some(targetVersion), followImports)

    // parse and format the document (and any imports)
    parser.Walker[Map[FileNode, Seq[String]]](docSource, Map.empty).walk { (doc, results) =>
      if (doc.version.value >= targetVersion) {
        throw new Exception(s"Cannot convert WDL version ${doc.version} to ${targetVersion}")
      }
      results + (doc.source -> formatter.formatDocument(doc))
    }
  }
}
