package wdlTools.syntax.v1

import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.v1.{WdlV1Lexer, WdlV1Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, ParseTreeListenerFactory}
import wdlTools.syntax.WdlVersion
import wdlTools.util.{FileSource, Options}

case class WdlV1Grammar(override val lexer: WdlV1Lexer,
                        override val parser: WdlV1Parser,
                        override val listenerFactories: Vector[ParseTreeListenerFactory],
                        override val docSource: FileSource,
                        override val opts: Options)
    extends Grammar(WdlVersion.V1, lexer, parser, listenerFactories, docSource, opts)

object WdlV1Grammar {
  def newInstance(docSource: FileSource,
                  listenerFactories: Vector[ParseTreeListenerFactory],
                  opts: Options): WdlV1Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(docSource.readBytes))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlV1Lexer(charStream)
    val parser = new WdlV1Parser(new CommonTokenStream(lexer))
    new WdlV1Grammar(lexer, parser, listenerFactories, docSource, opts)
  }
}
