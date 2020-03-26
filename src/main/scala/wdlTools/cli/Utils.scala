package wdlTools.cli

import java.net.URI
import java.nio.file.{Path, Paths}

import com.typesafe.config.ConfigFactory
import wdlTools.{FetchURL, URL}

object Utils {

  /**
    * The current wdlTools version.
    * @return
    */
  def version(): String = {
    val config = ConfigFactory.load("application.conf")
    config.getString("wdlTools.version")
  }

  /**
    * Determines the local path to a URI's file. The path will be the URI's file name relative to the parent; the
    * current working directory is used as the parent unless `parent` is specified. If the URI indicates a local path
    * and `ovewrite` is `true`, then the absolute local path is returned unless `parent` is specified.
    *
    * @param uri a URI, which might be a local path, a file:// uri, or an http(s):// uri)
    * @param parent The directory to which the local file should be made relative
    * @param selfOk Whether it is allowed to return the absolute path of a URI that is a local file, rather than making
    *               it relative to the current directory; ignored if `parent` is defined
    * @return The Path to the local file
    */
  def getLocalPath(uri: URI, parent: Option[Path] = None, selfOk: Boolean = true): Path = {
    uri.getScheme match {
      case null | "" | "file" =>
        val path = Paths.get(uri.getPath)

        if (parent.isDefined) {
          parent.get.resolve(path.getFileName)
        } else if (selfOk) {
          path.toAbsolutePath
        } else {
          Paths.get("").toAbsolutePath.resolve(path.getFileName)
        }
      case _ =>
        parent.getOrElse(Paths.get("")).resolve(Paths.get(uri.getPath).getFileName)
    }
  }

  def readFromFile(path: Path): String = {
    val source = io.Source.fromFile(path.toString)
    try source.getLines.mkString(System.lineSeparator())
    finally source.close()
  }

  def readFromUri(uri: URI, verbose: Boolean, localDirectories: Vector[Path]): String = {
    uri.getScheme match {
      case null | "" | "file" => readFromFile(getLocalPath(uri))
      case _                  => FetchURL(verbose, localDirectories).apply(URL(uri.toString))
    }
  }
}
