package wdlTools.cli

import java.net.URI
import java.nio.file.Path

import com.typesafe.config.ConfigFactory
import wdlTools.syntax
import wdlTools.util.Util.getLocalPath

object Util {

  /**
    * The current wdlTools version.
    * @return
    */
  def version(): String = {
    val config = ConfigFactory.load("application.conf")
    config.getString("wdlTools.version")
  }

  def readFromFile(path: Path): String = {
    val source = io.Source.fromFile(path.toString)
    try source.getLines.mkString(System.lineSeparator())
    finally source.close()
  }

  def readFromUri(uri: URI, conf: WdlToolsConf): (Path, String) = {
    val uriLocalPath = getLocalPath(uri)
    val sourceCode = uri.getScheme match {
      case null | "" | "file" => readFromFile(uriLocalPath)
      case _ =>
        syntax
          .FetchURL(conf.getSyntaxOptions(Set(uriLocalPath.getParent)))
          .apply(syntax.URL(uri.toString))
    }
    (uriLocalPath, sourceCode)
  }
}
