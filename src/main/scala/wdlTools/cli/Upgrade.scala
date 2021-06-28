package wdlTools.cli

import wdlTools.generators.code.{Upgrader, Utils => GeneratorUtils}
import dx.util.{FileNode, FileSourceResolver}
import wdlTools.syntax.{Parsers, SyntaxException, WdlVersion}
import wdlTools.types.{TypeCheckingRegime, TypeException, TypeInfer}

import scala.language.reflectiveCalls

case class Upgrade(conf: WdlToolsConf) extends Command {
  def parseAndCheck(source: FileNode,
                    wdlVersion: WdlVersion,
                    followImports: Boolean,
                    message: String): Unit = {
    val errorHandler = new CliTypeErrorHandler()
    val parsers = Parsers(followImports)
    val parser = parsers.getParser(wdlVersion)
    val checker = TypeInfer(TypeCheckingRegime.Moderate, errorHandler = Some(errorHandler))
    try {
      checker.apply(parser.parseDocument(source))
    } catch {
      case e: SyntaxException =>
        throw new Exception(s"Failed to parse ${source}; ${message}", e)
      case e: TypeException =>
        throw new Exception(s"Failed to type-check ${source}; ${message}", e)
    }
    if (errorHandler.hasTypeErrors) {
      throw new Exception(
          s"Failed to type-check ${source}; ${message}:\n${errorHandler.toString}"
      )
    }
  }

  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.upgrade.uri())
    val srcVersion = conf.upgrade.srcVersion.toOption
    val destVersion = conf.upgrade.destVersion()
    val outputDir = conf.upgrade.outputDir.toOption
    val overwrite = conf.upgrade.overwrite()
    val followImports = conf.upgrade.followImports()
    val upgrader = Upgrader(followImports)
    // write out upgraded versions
    val documents = upgrader.upgrade(docSource, srcVersion, destVersion)
    val upgraded = GeneratorUtils.writeDocuments(documents, outputDir, overwrite)
    if (conf.upgrade.check() && upgraded.nonEmpty) {
      upgraded.head._1 match {
        case fn: FileNode =>
          parseAndCheck(
              fn,
              destVersion,
              followImports,
              "this is likely due to a bug in the WDL code generator - please report this issue"
          )
      }
    }
  }
}
