package wdlTools.util

import java.nio.file.{Files, Path, Paths}

import wdlTools.util.Verbosity._

import scala.io.Source

/**
  * Source code from a URL
  * @param url the source URL
  * @param lines the lines of the file
  */
case class SourceCode(url: URL, lines: Seq[String]) {
  lazy override val toString: String = lines.mkString(System.lineSeparator())
}

object SourceCode {

  // Examples for URLs:
  //   http://google.com/A.txt
  //   https://google.com/A.txt
  //   file://A/B.txt
  //   foo.txt
  //
  // Follow the URL and retrieve the content as a string.
  case class Loader(opts: Options) {
    // This is a local file. Look for it in all the possible
    // search locations.
    private def fetchLocalFile(filepath: String): Seq[String] = {
      val path: Path = Paths.get(filepath)
      val resolved: Option[Path] = if (Files.exists(path)) {
        Some(path)
      } else {
        // search in all directories where imports may be found
        opts.localDirectories.map(d => d.resolve(filepath)).collectFirst {
          case fp if Files.exists(fp) => fp
        }
      }
      if (resolved.isEmpty) {
        throw new Exception(s"Could not find local file ${filepath}")
      }
      Util.readLinesFromFile(resolved.get)
    }

    private def fetchHttpAddress(urlAddr: String): Seq[String] = {
      val src = Source.fromURL(urlAddr)
      try {
        src.getLines().toVector
      } finally {
        src.close()
      }
    }

    def apply(url: URL): SourceCode = {
      val p: String = url.addr
      if (opts.verbosity > Normal) {
        System.out.println(s"looking for ${p}")
      }

      val lines = if (p contains "://") {
        val components = p.split("://")
        val protocol = components.head
        protocol match {
          case "http"  => fetchHttpAddress(p)
          case "https" => fetchHttpAddress(p)
          case "file"  => fetchLocalFile(components(1))
          case _       => throw new Exception(s"unknown protocol ${protocol} in path ${p}")
        }
      } else {
        // no recognizable protocol, assuming a file
        fetchLocalFile(p)
      }

      SourceCode(url, lines)
    }
  }
}
