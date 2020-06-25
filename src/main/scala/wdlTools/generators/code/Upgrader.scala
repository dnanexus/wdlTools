package wdlTools.generators.code

import java.net.URL

import wdlTools.syntax
import wdlTools.syntax.WdlVersion
import wdlTools.util.{BasicOptions, Options}

case class Upgrader(opts: Options) {
  private val parsers = syntax.Parsers(opts)

  def upgrade(url: URL,
              srcVersion: Option[WdlVersion] = None,
              destVersion: WdlVersion = WdlVersion.V1): Map[URL, Seq[String]] = {
    val parser = if (srcVersion.isDefined) {
      parsers.getParser(srcVersion.get)
    } else {
      parsers.getParser(url)
    }

    // the parser will follow imports, so the formatter should not
    val formatter = WdlV1Formatter(
        BasicOptions(
            opts.localDirectories,
            logger = opts.logger,
            antlr4Trace = opts.antlr4Trace
        )
    )

    // parse and format the document (and any imports)
    parser.getDocumentWalker[Map[URL, Seq[String]]](url, Map.empty).walk { (doc, results) =>
      if (doc.version.value >= destVersion) {
        throw new Exception(s"Cannot convert WDL version ${doc.version} to ${destVersion}")
      }
      results + (doc.sourceUrl.get -> formatter.formatDocument(doc))
    }
  }
}
