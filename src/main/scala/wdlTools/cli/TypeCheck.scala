package wdlTools.cli

import java.io.{FileOutputStream, PrintStream}
import java.net.URL
import java.nio.file.Files

import spray.json.{JsArray, JsNumber, JsObject, JsString}
import wdlTools.syntax.{Parsers, SyntaxException, TextSource}
import wdlTools.types.TypedAbstractSyntax.Element
import wdlTools.types.TypedAbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.types.WdlTypes.T
import wdlTools.types.{TypeException, TypeInfer, TypedAbstractSyntax, TypedAbstractSyntaxTreeWalker}

import scala.collection.mutable
import scala.io.AnsiColor

case class TypeCheck(conf: WdlToolsConf) extends Command {
  private def errorsToJson(errors: Map[URL, Vector[(String, TextSource)]]): JsObject = {
    def getError(reason: String, textSource: TextSource): JsObject = {
      JsObject(
          Map(
              "reason" -> JsString(reason),
              "startLine" -> JsNumber(textSource.line),
              "startCol" -> JsNumber(textSource.col),
              "endLine" -> JsNumber(textSource.endLine),
              "endCol" -> JsNumber(textSource.endCol)
          )
      )
    }
    JsObject(Map("sources" -> JsArray(errors.map {
      case (url, docErrors) =>
        JsObject(
            Map("source" -> JsString(url.toString),
                "errors" -> JsArray(docErrors.map(err => getError(err._1, err._2))))
        )
    }.toVector)))
  }

  private def printErrors(errors: Map[URL, Vector[(String, TextSource)]],
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
        case (url, docErrors) => (s"Type-check errors in ${url}", docErrors)
      }
      val border1 = "=" * msg.length
      val border2 = "-" * msg.length
      printer.println(border1)
      printer.println(colorMsg(msg, AnsiColor.BLUE))
      printer.println(border1)
      printer.println(colorMsg("Line:Col | Description", AnsiColor.BOLD))
      printer.println(border2)
      docErrors.sortWith(_._2 < _._2).foreach {
        case (reason, textSource) =>
          printer.println(f"${textSource}%-9s| ${reason} |")
      }
    }
  }

  override def apply(): Unit = {
    val url = conf.check.url()
    val opts = conf.check.getOptions
    val parsers = Parsers(opts)
    val checker = TypeInfer(opts)
    try {
      val tDoc = checker.apply(parsers.parseDocument(url))._1
      if (!opts.errorAsException) {
        // traverse the tree to collect invalid nodes and display to the user
        val errors: mutable.Map[URL, mutable.Buffer[(String, TextSource)]] = mutable.HashMap.empty
        val walker = new TypedAbstractSyntaxTreeWalker(opts) {
          override def visitDocument(ctx: VisitorContext[TypedAbstractSyntax.Document]): Unit = {
            if (!errors.contains(ctx.docSourceUrl)) {
              errors(ctx.docSourceUrl) = mutable.Buffer.empty
            }
            super.visitDocument(ctx)
          }

          override def visitInvalid[P <: Element](reason: String,
                                                  originalType: Option[T],
                                                  ctx: VisitorContext[P]): Unit = {
            errors(ctx.docSourceUrl).append((reason, ctx.element.text))
          }
        }
        walker.apply(tDoc)
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
          val finalErrors = errors.view.mapValues(_.toVector).toMap
          if (conf.check.json()) {
            val js = errorsToJson(finalErrors).prettyPrint
            printer.println(js)
          } else {
            printErrors(finalErrors, printer, effects = !toFile)
          }
          if (toFile) {
            printer.close()
          }
        }
      }
    } catch {
      case e: SyntaxException => println(s"Failed to parse WDL document: ${e.getMessage}")
      case e: TypeException   => println(s"Failed to type-check WDL document: ${e.getMessage}")
    }
  }
}
