package wdlTools.syntax

import java.net.URL
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

// Based on Patrick Magee's error handling code (https://github.com/patmagee/wdl4j)
//
case class WdlAggregatingErrorListener(docSourceURL : Option[URL]) extends BaseErrorListener {

  private var errors = Vector.empty[SyntaxError]

  // This is called by the antlr grammar, we have no control
  // over this behavior.
  override def syntaxError(recognizer: Recognizer[_, _],
                           offendingSymbol: Any,
                           line: Int,
                           charPositionInLine: Int,
                           msg: String,
                           e: RecognitionException): Unit = {
    val symbolText =
      offendingSymbol match {
        case tok: Token =>
          tok.getText
        case _ =>
          offendingSymbol.toString
      }
    val err = SyntaxError(docSourceURL, symbolText, line, charPositionInLine, msg)
    errors = errors :+ err
  }

  def getErrors() : Vector[SyntaxError] = {
    return errors
  }

  def hasErrors() : Boolean = {
    errors.nonEmpty
  }
}
