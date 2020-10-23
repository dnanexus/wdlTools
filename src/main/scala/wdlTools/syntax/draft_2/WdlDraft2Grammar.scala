package wdlTools.syntax.draft_2

import java.nio.ByteBuffer

import org.antlr.v4.runtime.{CodePointBuffer, CodePointCharStream, CommonTokenStream}
import org.openwdl.wdl.parser.draft_2.{WdlDraft2Lexer, WdlDraft2Parser}
import wdlTools.syntax.Antlr4Util.{Grammar, ParseTreeListenerFactory}
import wdlTools.syntax.WdlVersion
import dx.util.{FileNode, Logger}

case class WdlDraft2Grammar(override val lexer: WdlDraft2Lexer,
                            override val parser: WdlDraft2Parser,
                            override val listenerFactories: Vector[ParseTreeListenerFactory],
                            override val docSource: FileNode,
                            override val logger: Logger)
    extends Grammar(WdlVersion.Draft_2, lexer, parser, listenerFactories, docSource, logger)

object WdlDraft2Grammar {
  def newInstance(fileSource: FileNode,
                  listenerFactories: Vector[ParseTreeListenerFactory],
                  logger: Logger = Logger.get): WdlDraft2Grammar = {
    val codePointBuffer: CodePointBuffer =
      CodePointBuffer.withBytes(ByteBuffer.wrap(fileSource.readBytes))
    val charStream = CodePointCharStream.fromBuffer(codePointBuffer)
    val lexer = new WdlDraft2Lexer(charStream)
    val parser = new WdlDraft2Parser(new CommonTokenStream(lexer))
    new WdlDraft2Grammar(lexer, parser, listenerFactories, fileSource, logger)
  }
}
