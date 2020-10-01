package wdlTools.syntax.v2

import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.v2.{WdlV2Lexer, WdlV2Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, ParseTreeListenerFactory}
import wdlTools.syntax.WdlVersion
import wdlTools.util.{FileNode, Logger}

case class WdlV2Grammar(override val lexer: WdlV2Lexer,
                        override val parser: WdlV2Parser,
                        override val listenerFactories: Vector[ParseTreeListenerFactory],
                        override val docSource: FileNode,
                        override val logger: Logger)
    extends Grammar(WdlVersion.V2, lexer, parser, listenerFactories, docSource, logger)

object WdlV2Grammar {
  def newInstance(docSource: FileNode,
                  listenerFactories: Vector[ParseTreeListenerFactory],
                  logger: Logger = Logger.get): WdlV2Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(docSource.readBytes))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlV2Lexer(charStream)
    val parser = new WdlV2Parser(new CommonTokenStream(lexer))
    new WdlV2Grammar(lexer, parser, listenerFactories, docSource, logger)
  }
}
