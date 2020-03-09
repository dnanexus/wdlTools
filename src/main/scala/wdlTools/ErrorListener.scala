package wdlTools

//import collection.JavaConverters._
//import java.nio.ByteBuffer
//import org.antlr.v4.runtime._

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

case class SyntaxError(symbol: String, line: Int, charPositionInLine: Int, msg: String)

case class ErrorListener(assertNoErrors: Boolean, verbose: Boolean) extends BaseErrorListener {
  var errors = Vector.empty[SyntaxError]

  override def syntaxError(recognizer: Recognizer[_, _],
                           offendingSymbol: Any,
                           line: Int,
                           charPositionInLine: Int,
                           msg: String,
                           e: RecognitionException): Unit = {
    val symbolText =
      if (offendingSymbol.isInstanceOf[Token]) {
        val tok = offendingSymbol.asInstanceOf[Token]
        tok.getText()
      } else {
        offendingSymbol.toString
      }
    val err = new SyntaxError(symbolText, line, charPositionInLine, msg)
    errors = errors :+ err
  }

  def getAllErrors: Vector[SyntaxError] = errors
}
