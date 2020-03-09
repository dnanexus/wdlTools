package wdlTools

import ConcreteSyntax.URL

import collection.JavaConverters._
import java.nio.file.{Path, Paths, Files}
import scala.io.Source

// Examples for URLs:
//   http://google.com/A.txt
//   https://google.com/A.txt
//   file://A/B.txt
//   foo.txt
//
// Follow the URL and retrieve the content as a string.
case class FetchURL(verbose: Boolean, localDirectories: Vector[Path]) {

  private def read(p: Path): String = {
    Files.readAllLines(p).asScala.mkString(System.lineSeparator())
  }

  // This is a local file. Look for it in all the possible
  // search locations.
  private def fetchLocalFile(filepath: String): String = {
    val path: Path = Paths.get(filepath)
    if (Files.exists(path))
      return read(path)

    // search in all directories where imports may be found
    for (d <- localDirectories) {
      val fp: Path = d.resolve(filepath)
      if (Files.exists(fp))
        return read(fp)
    }

    throw new Exception(s"Could not find local file ${filepath}")
  }

  private def fetchHttpAddress(urlAddr: String): String = {
    val lines = Source.fromURL(urlAddr)
    lines.mkString
  }

  def apply(url: URL): String = {
    val p: String = url.addr
    if (verbose)
      System.out.println(s"looking for ${p}")

    if (p contains "://") {
      val components = p.split("://").toList
      val protocol = components(0)
      protocol match {
        case "http"  => fetchHttpAddress(p)
        case "https" => fetchHttpAddress(p)
        case "file"  => fetchLocalFile(p)
        case _       => throw new Exception(s"unknown protocol ${protocol} in path ${p}")
      }
    } else {
      // no recognizable protocol, assuming a file
      fetchLocalFile(p)
    }
  }
}
