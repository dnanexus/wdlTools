package wdlTools.cli

import java.io.{FileOutputStream, PrintStream}
import java.nio.file.Files

import spray.json.{JsArray, JsNumber, JsObject, JsString}
import wdlTools.syntax.{Parsers, SyntaxException}
import wdlTools.types.{TypeError, TypeException, TypeInfer}
import wdlTools.util.FileSource

import scala.io.AnsiColor
import scala.language.reflectiveCalls

case class TypeCheck(conf: WdlToolsConf) extends Command {
  private def errorsToJson(errors: Map[FileSource, Vector[TypeError]]): JsObject = {
    def getError(err: TypeError): JsObject = {
      JsObject(
          Map(
              "reason" -> JsString(err.reason),
              "startLine" -> JsNumber(err.loc.line),
              "startCol" -> JsNumber(err.loc.col),
              "endLine" -> JsNumber(err.loc.endLine),
              "endCol" -> JsNumber(err.loc.endCol)
          )
      )
    }
    JsObject(Map("sources" -> JsArray(errors.map {
      case (uri, docErrors) =>
        JsObject(
            Map("source" -> JsString(uri.toString),
                "errors" -> JsArray(docErrors.map(err => getError(err))))
        )
    }.toVector)))
  }

  private def printErrors(errors: Map[FileSource, Vector[TypeError]],
                          printer: PrintStream,
                          effects: Boolean): Unit = {
    def colorMsg(msg: String, color: String): String = {
      if (effects) {
        s"${color}${msg}${AnsiColor.RESET}"
      } else {
        msg
      }
    }
    errors.foreach { item =>
      val (msg, docErrors) = item match {
        case (uri, docErrors) => (s"Type-check errors in ${uri}", docErrors)
      }
      val border1 = "=" * msg.length
      val border2 = "-" * msg.length
      printer.println(border1)
      printer.println(colorMsg(msg, AnsiColor.BLUE))
      printer.println(border1)
      printer.println(colorMsg("Line:Col | Description", AnsiColor.BOLD))
      printer.println(border2)
      docErrors.sortWith(_.loc < _.loc).foreach { err =>
        printer.println(f"${err.loc}%-9s| ${err.reason}")
      }
    }
  }

  override def apply(): Unit = {
    val opts = conf.check.getOptions
    val docSource = opts.fileResolver.resolve(conf.check.uri())
    val parsers = Parsers(opts)
    var errors: Map[FileSource, Vector[TypeError]] = Map.empty

    def errorHandler(typeErrors: Vector[TypeError]): Boolean = {
      typeErrors.groupBy(_.loc.source).foreach {
        case (docSource, docErrors) =>
          errors += (docSource -> (errors.getOrElse(docSource, Vector.empty) ++ docErrors))
        case other => throw new RuntimeException(s"Unexpected ${other}")
      }
      false
    }

    val checker = TypeInfer(opts, errorHandler = Some(errorHandler))

    try {
      checker.apply(parsers.parseDocument(docSource))
    } catch {
      case e: SyntaxException => println(s"Failed to parse WDL document: ${e.getMessage}")
      case e: TypeException   => println(s"Failed to type-check WDL document: ${e.getMessage}")
    }

    if (errors.nonEmpty) {
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
        val js = errorsToJson(errors).prettyPrint
        printer.println(js)
      } else {
        printErrors(errors, printer, effects = !toFile)
      }
      if (toFile) {
        printer.close()
      }
    }
  }
}
