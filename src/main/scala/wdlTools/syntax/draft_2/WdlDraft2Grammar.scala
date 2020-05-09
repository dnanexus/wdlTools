package wdlTools.syntax.draft_2

import java.net.URL
import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.draft_2.{WdlDraft2Lexer, WdlDraft2Parser}
import wdlTools.syntax.Antlr4Util.Grammar
import wdlTools.util.{Options, SourceCode}

case class WdlDraft2Grammar(override val lexer: WdlDraft2Lexer,
                            override val parser: WdlDraft2Parser,
                            override val docSourceUrl: Option[URL] = None,
                            override val opts: Options)
    extends Grammar(lexer, parser, docSourceUrl, opts)

object WdlDraft2Grammar {
  def newInstance(sourceCode: SourceCode, opts: Options): WdlDraft2Grammar = {
    newInstance(sourceCode.toString, Some(sourceCode.url), opts)
  }

  def newInstance(text: String,
                  docSourceUrl: Option[URL] = None,
                  opts: Options): WdlDraft2Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(text.getBytes()))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlDraft2Lexer(charStream)
    val parser = new WdlDraft2Parser(new CommonTokenStream(lexer))
    new WdlDraft2Grammar(lexer, parser, docSourceUrl, opts)
  }
}
