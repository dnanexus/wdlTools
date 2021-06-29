package wdlTools.cli

import dx.util.FileNode
import spray.json.{JsArray, JsNumber, JsObject, JsString}
import wdlTools.types.{TypeErrorHandler, TypeError}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.io.AnsiColor

class CliTypeErrorHandler extends TypeErrorHandler {
  private var errors: Map[FileNode, Vector[TypeError]] = Map.empty

  def hasTypeErrors: Boolean = errors.nonEmpty

  def getErrors: Map[FileNode, Vector[TypeError]] = errors

  override def handleTypeErrors(typeErrors: Vector[TypeError]): Boolean = {
    typeErrors.groupBy(_.loc.source).foreach {
      case (docSource, docErrors) =>
        errors += (docSource -> (errors.getOrElse(docSource, Vector.empty) ++ docErrors))
      case other => throw new RuntimeException(s"Unexpected ${other}")
    }
    false
  }

  def errorsToJson: JsObject = {
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

  def printErrors(printer: PrintStream, effects: Boolean): Unit = {
    def colorMsg(msg: String, color: String): String = {
      if (effects) {
        s"${color}${msg}${AnsiColor.RESET}"
      } else {
        msg
      }
    }
    errors.foreach {
      case (uri, docErrors) =>
        val sortedErrors = docErrors.sortWith(_.loc < _.loc)
        // determine first column with from max line and column
        val firstColWidth = Math.max(
            ((
                sortedErrors.last.loc.endLine.toString.length +
                  sortedErrors.last.loc.endCol.toString.length
            ) * 2) + 3,
            9
        )
        val msg = s"Type-check errors in ${uri}"
        val border1 = "=" * msg.length
        val border2 = "-" * msg.length
        printer.println(border1)
        printer.println(colorMsg(msg, AnsiColor.BLUE))
        printer.println(border1)
        val title = String.format("%-" + firstColWidth.toString + "s| Description", "Line:Col")
        printer.println(colorMsg(title, AnsiColor.BOLD))
        printer.println(border2)
        sortedErrors.foreach { err =>
          printer.println(
              String.format(
                  "%-" + firstColWidth.toString + "s| %s",
                  err.loc.locationString,
                  err.reason
              )
          )
        }
    }
  }

  override def toString: String = {
    val msgStream = new ByteArrayOutputStream()
    printErrors(new PrintStream(msgStream), effects = true)
    msgStream.toString
  }
}
