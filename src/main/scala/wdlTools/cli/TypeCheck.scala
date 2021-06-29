package wdlTools.cli

import java.io.{FileOutputStream, PrintStream}
import java.nio.file.Files

import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.{TypeException, TypeInfer}
import dx.util.FileSourceResolver

import scala.language.reflectiveCalls

case class TypeCheck(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.check.uri())
    val parsers = Parsers(followImports = true)
    val errorHandler = new CliTypeErrorHandler()
    val checker = TypeInfer(conf.check.regime(), errorHandler = Some(errorHandler))

    try {
      checker.apply(parsers.parseDocument(docSource))
    } catch {
      case e: SyntaxException => println(s"Failed to parse WDL document: ${e.getMessage}")
      case e: TypeException   => println(s"Failed to type-check WDL document: ${e.getMessage}")
    }

    if (errorHandler.hasTypeErrors) {
      // format as json or text and write to file or stdout
      val outputFile = conf.check.outputFile.toOption
      val toFile = outputFile.isDefined
      val printer: PrintStream = if (toFile) {
        val resolved = outputFile.get
        if (!conf.check.overwrite() && Files.exists(resolved)) {
          throw new Exception(s"File already exists: ${resolved}")
        }
        val fos = new FileOutputStream(outputFile.get.toFile)
        new PrintStream(fos, true)
      } else {
        System.out
      }
      if (conf.check.json()) {
        val js = errorHandler.errorsToJson.prettyPrint
        printer.println(js)
      } else {
        errorHandler.printErrors(printer, effects = !toFile)
      }
      if (toFile) {
        printer.close()
      }
    }
  }
}
