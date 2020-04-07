package wdlTools.formatter

import java.net.URL

import wdlTools.syntax
import wdlTools.syntax.WdlVersion
import wdlTools.util.Options

case class Upgrader(opts: Options) {
  private val parsers = syntax.Parsers(opts)

  def upgrade(url: URL,
              srcVersion: Option[WdlVersion] = None,
              destVersion: WdlVersion = WdlVersion.V1_0): Map[URL, Seq[String]] = {
    val parser = if (srcVersion.isDefined) {
      parsers.getParser(srcVersion.get)
    } else {
      parsers.getParser(url)
    }

    // the parser will follow imports, so the formatter should not
    val formatter = V1_0Formatter(opts.clone(followImports = false))

    // parse and format the document (and any imports)
    val walker = parser.Walker[Seq[String]](url)
    walker.walk { (docUrl, doc, results) =>
      if (doc.version >= destVersion) {
        throw new Exception(s"Cannot convert WDL version ${doc.version} to ${destVersion}")
      }
      results(docUrl) = formatter.formatDocument(doc)
    }
  }
}
