package wdlTools.syntax

import java.net.URL
import java.nio.ByteBuffer

import org.antlr.v4.runtime.tree.TerminalNode

import collection.JavaConverters._
import org.antlr.v4.runtime.{
  BufferedTokenStream,
  CharStream,
  CodePointBuffer,
  CodePointCharStream,
  CommonTokenStream,
  Lexer,
  Parser,
  ParserRuleContext,
  Token
}
import wdlTools.syntax
import wdlTools.util.{Options, SourceCode, Verbosity}

import scala.collection.mutable

object Antlr4Util {
  def getSourceText(token: Token, docSourceURL: Option[URL]): TextSource = {
    syntax.TextSource(line = token.getLine, col = token.getCharPositionInLine, url = docSourceURL)
  }

  case class CommentListener(tokenStream: BufferedTokenStream,
                             channelIndex: Int,
                             docSourceUrl: Option[URL] = None,
                             comments: mutable.Map[Int, Comment] = mutable.HashMap.empty)
      extends AllParseTreeListener {
    def addComments(tokens: Vector[Token]): Unit = {
      tokens.foreach { tok =>
        val source = Antlr4Util.getSourceText(tok, docSourceUrl)
        if (comments.contains(source.line)) {
          // TODO: should this be an error?
        } else {
          comments(source.line) = Comment(tok.getText, source)
        }
      }
    }

    override def enterEveryRule(ctx: ParserRuleContext): Unit = {
      if (ctx.getStart.getTokenIndex >= 0) {
        val beforeComments =
          tokenStream.getHiddenTokensToLeft(ctx.getStart.getTokenIndex, channelIndex)
        if (beforeComments != null) {
          addComments(beforeComments.asScala.toVector)
        }
      }
      if (ctx.getStop.getTokenIndex >= 0) {
        val afterComments =
          tokenStream.getHiddenTokensToRight(ctx.getStop.getTokenIndex, channelIndex)
        if (afterComments != null) {
          addComments(afterComments.asScala.toVector)
        }
      }
    }
  }

  case class Grammar[L <: Lexer, P <: Parser](lexer: L,
                                              parser: P,
                                              errListener: ErrorListener,
                                              comments: mutable.Map[Int, Comment],
                                              opts: Options) {
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

    def getSourceText(ctx: ParserRuleContext, docSourceURL: Option[URL]): TextSource = {
      Antlr4Util.getSourceText(ctx.start, docSourceURL)
    }

    def getSourceText(symbol: TerminalNode, docSourceURL: Option[URL]): TextSource = {
      Antlr4Util.getSourceText(symbol.getSymbol, docSourceURL)
    }
  }

  abstract class GrammarFactory[L <: Lexer, P <: Parser](opts: Options,
                                                         commentChannelName: String = "COMMENTS") {
    def createGrammar(sourceCode: SourceCode): Grammar[L, P] = {
      createGrammar(sourceCode.toString, Some(sourceCode.url))
    }

    def createGrammar(inp: String, docSourceUrl: Option[URL] = None): Grammar[L, P] = {
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

      val comments: mutable.Map[Int, Comment] = mutable.HashMap.empty

      parser.addParseListener(
          CommentListener(
              parser.getTokenStream.asInstanceOf[BufferedTokenStream],
              lexer.getChannelNames.indexOf(commentChannelName),
              docSourceUrl,
              comments
          )
      )

      Grammar(lexer, parser, errListener, comments, opts)
    }

    def createLexer(charStream: CharStream): L

    def createParser(tokenStream: CommonTokenStream): P
  }
}
