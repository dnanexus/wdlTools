package wdlTools.generators

import java.net.URL

import scala.collection.mutable

case class DockerfileGenerator(renderer: Renderer = Renderer(),
                               generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty) {

  val TEMPLATE = "/templates/project/Dockerfile.ssp"

  def apply(url: URL): Unit = {
    generatedFiles(url) = renderer.render(TEMPLATE)
  }
}
