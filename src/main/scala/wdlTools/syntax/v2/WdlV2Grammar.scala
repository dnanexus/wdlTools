package wdlTools.syntax.v2

import java.net.URL
import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.v2.{WdlV2Lexer, WdlV2Parser}
import wdlTools.syntax.Antlr4Util.Grammar
import wdlTools.util.{Options, SourceCode}

case class WdlV2Grammar(override val lexer: WdlV2Lexer,
                        override val parser: WdlV2Parser,
                        override val docSourceUrl: Option[URL] = None,
                        override val opts: Options)
    extends Grammar(lexer, parser, docSourceUrl, opts)

object WdlV2Grammar {
  def newInstance(sourceCode: SourceCode, opts: Options): WdlV2Grammar = {
    newInstance(sourceCode.toString, Some(sourceCode.url), opts)
  }

  def newInstance(text: String, docSourceUrl: Option[URL] = None, opts: Options): WdlV2Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(text.getBytes()))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlV2Lexer(charStream)
    val parser = new WdlV2Parser(new CommonTokenStream(lexer))
    new WdlV2Grammar(lexer, parser, docSourceUrl, opts)
  }
}
