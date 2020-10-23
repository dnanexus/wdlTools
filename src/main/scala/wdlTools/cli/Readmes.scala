package wdlTools.cli

import java.nio.file.{FileAlreadyExistsException, Files, Path}

import wdlTools.generators.Renderer
import wdlTools.generators.project.ReadmeGenerator
import wdlTools.syntax.Parsers
import dx.util.{FileSourceResolver, FileUtils}

import scala.language.reflectiveCalls

case class Readmes(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val overwrite = conf.docgen.overwrite()
    val outputDir = conf.docgen.outputDir.toOption.map { path =>
      val absPath = path.toAbsolutePath
      if (!overwrite && Files.exists(absPath)) {
        throw new Exception(s"Directory ${absPath} already exists and overwrite = false")
      }
      absPath
    }
    val docSource = FileSourceResolver.get.resolve(conf.readmes.uri())
    val parsers = Parsers(conf.readmes.followImports())
    val readmes =
      parsers.getDocumentWalker[Map[Path, String]](docSource, Map.empty[Path, String]).walk {
        (doc, results) =>
          results ++ ReadmeGenerator(conf.readmes.developerReadmes(), Renderer.default).apply(doc)
      }
    readmes.foreach {
      case (path, content) =>
        val outputFile = outputDir.map(_.resolve(path.getFileName)).getOrElse(path)
        if (!overwrite && Files.exists(outputFile)) {
          throw new FileAlreadyExistsException(
              s"File ${outputFile} already exists and overwrite = false"
          )
        }
        FileUtils.writeFileContent(outputFile, content)
    }
  }
}
