package wdlTools.cli

import wdlTools.generators.code.Upgrader
import dx.util.{FileNode, FileSourceResolver}

import scala.language.reflectiveCalls

case class Upgrade(conf: WdlToolsConf) extends Command {
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
    val upgraded = writeDocuments(documents, outputDir, overwrite)
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
