package wdlTools.syntax

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

import wdlTools.syntax.Util.Options

case class SyntaxError(symbol: String, line: Int, charPositionInLine: Int, msg: String)

case class ErrorListener(conf: Options) extends BaseErrorListener {
  var errors = Vector.empty[SyntaxError]

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
    val err = SyntaxError(symbolText, line, charPositionInLine, msg)
    errors = errors :+ err
  }

  def getAllErrors: Vector[SyntaxError] = errors
}
