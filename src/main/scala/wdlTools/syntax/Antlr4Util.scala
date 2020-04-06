package wdlTools.syntax

import java.nio.ByteBuffer
import collection.JavaConverters._
import org.antlr.v4.runtime.{
  BufferedTokenStream,
  CharStream,
  CodePointBuffer,
  CodePointCharStream,
  CommonTokenStream,
  Lexer,
  Parser,
  ParserRuleContext
}
import wdlTools.util.{Options, Verbosity}

object Antlr4Util {
  case class Grammar[L <: Lexer, P <: Parser](lexer: L,
                                              parser: P,
                                              errListener: ErrorListener,
                                              commentChannelName: String,
                                              opts: Options) {
    val commentChannel: Int = lexer.getChannelNames.indexOf(commentChannelName)
    require(commentChannel > 0)

    def verify(): Unit = {
      // check if any errors were found
      val errors: Vector[SyntaxError] = errListener.getAllErrors
      if (errors.nonEmpty) {
        if (opts.verbosity > Verbosity.Quiet) {
          for (err <- errors) {
            System.out.println(err)
          }
        }
        throw new Exception(s"${errors.size} syntax errors were found, stopping")
      }
    }

    def getComments(ctx: ParserRuleContext, before: Boolean = true): Seq[String] = {
      val start = ctx.getStart
      val idx = start.getTokenIndex
      if (idx >= 0) {
        val tokenStream = parser.getTokenStream.asInstanceOf[BufferedTokenStream]
        val commentTokens = if (before) {
          tokenStream.getHiddenTokensToLeft(idx, commentChannel)
        } else {
          tokenStream.getHiddenTokensToRight(idx, commentChannel)
        }
        if (commentTokens != null) {
          return commentTokens.asScala.map(_.getText).toVector
        }
      }
      Vector.empty[String]
    }
  }

  abstract class GrammarFactory[L <: Lexer, P <: Parser](opts: Options,
                                                         commentChannelName: String = "COMMENTS") {
    def createGrammar(inp: String): Grammar[L, P] = {
      val codePointBuffer: CodePointBuffer =
        CodePointBuffer.withBytes(ByteBuffer.wrap(inp.getBytes()))
      val lexer: L = createLexer(CodePointCharStream.fromBuffer(codePointBuffer))
      val parser: P = createParser(new CommonTokenStream(lexer))

      // setting up our own error handling
      val errListener = ErrorListener(opts)
      lexer.removeErrorListeners()
      lexer.addErrorListener(errListener)
      parser.removeErrorListeners()
      parser.addErrorListener(errListener)

      if (opts.antlr4Trace) {
        parser.setTrace(true)
      }

      Grammar(lexer, parser, errListener, commentChannelName, opts)
    }

    def createLexer(charStream: CharStream): L

    def createParser(tokenStream: CommonTokenStream): P
  }
}
