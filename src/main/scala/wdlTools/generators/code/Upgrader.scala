package wdlTools.generators.code

import wdlTools.syntax
import wdlTools.syntax.WdlVersion
import wdlTools.util.{FileSource, Options}

case class Upgrader(opts: Options) {
  private val parsers = syntax.Parsers(opts)

  def upgrade(docSource: FileSource,
              srcVersion: Option[WdlVersion] = None,
              destVersion: WdlVersion = WdlVersion.V1): Map[FileSource, Seq[String]] = {
    val parser = if (srcVersion.isDefined) {
      parsers.getParser(srcVersion.get)
    } else {
      parsers.getParser(docSource)
    }

    // the parser will follow imports, so the formatter should not
    val formatter = WdlV1Formatter(opts)

    // parse and format the document (and any imports)
    parser.Walker[Map[FileSource, Seq[String]]](docSource, Map.empty).walk { (doc, results) =>
      if (doc.version.value >= destVersion) {
        throw new Exception(s"Cannot convert WDL version ${doc.version} to ${destVersion}")
      }
      results + (doc.source -> formatter.formatDocument(doc))
    }
  }
}
