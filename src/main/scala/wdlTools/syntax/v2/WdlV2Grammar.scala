package wdlTools.syntax.v2

import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.v2.{WdlV2Lexer, WdlV2Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, ParseTreeListenerFactory}
import wdlTools.syntax.WdlVersion
import wdlTools.util.{FileSource, Options}

case class WdlV2Grammar(override val lexer: WdlV2Lexer,
                        override val parser: WdlV2Parser,
                        override val listenerFactories: Vector[ParseTreeListenerFactory],
                        override val docSource: FileSource,
                        override val opts: Options)
    extends Grammar(WdlVersion.V2, lexer, parser, listenerFactories, docSource, opts)

object WdlV2Grammar {
  def newInstance(docSource: FileSource,
                  listenerFactories: Vector[ParseTreeListenerFactory],
                  opts: Options): WdlV2Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(docSource.readBytes))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlV2Lexer(charStream)
    val parser = new WdlV2Parser(new CommonTokenStream(lexer))
    new WdlV2Grammar(lexer, parser, listenerFactories, docSource, opts)
  }
}
