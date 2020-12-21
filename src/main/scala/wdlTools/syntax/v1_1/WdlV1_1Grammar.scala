package wdlTools.syntax.v1_1

import java.nio.ByteBuffer
import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import wdlTools.syntax.Antlr4Util.{Grammar, ParseTreeListenerFactory}
import wdlTools.syntax.WdlVersion
import dx.util.{FileNode, Logger}
import org.openwdl.wdl.parser.v1_1.{WdlV1_1Lexer, WdlV1_1Parser}

case class WdlV1_1Grammar(override val lexer: WdlV1_1Lexer,
                          override val parser: WdlV1_1Parser,
                          override val listenerFactories: Vector[ParseTreeListenerFactory],
                          override val docSource: FileNode,
                          override val logger: Logger)
    extends Grammar(WdlVersion.V2, lexer, parser, listenerFactories, docSource, logger)

object WdlV1_1Grammar {
  def newInstance(docSource: FileNode,
                  listenerFactories: Vector[ParseTreeListenerFactory],
                  logger: Logger = Logger.get): WdlV1_1Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(docSource.readBytes))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlV1_1Lexer(charStream)
    val parser = new WdlV1_1Parser(new CommonTokenStream(lexer))
    new WdlV1_1Grammar(lexer, parser, listenerFactories, docSource, logger)
  }
}
