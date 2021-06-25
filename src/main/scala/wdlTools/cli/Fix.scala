package wdlTools.cli

import wdlTools.generators.code.Fixer
import dx.util.{FileSourceResolver, FileUtils}

import scala.language.reflectiveCalls

case class Fix(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val fileResolver = FileSourceResolver.get
    val fixer = Fixer(conf.fix.followImports(), fileResolver)
    val docSource = fileResolver.resolve(conf.fix.uri())
    fixer.fix(
        docSource,
        conf.fix.outputDir.toOption.getOrElse(FileUtils.cwd(absolute = true)),
        conf.fix.overwrite(),
        conf.fix.srcVersion.toOption,
        Some(new CliTypeErrorHandler())
    )
  }
}
