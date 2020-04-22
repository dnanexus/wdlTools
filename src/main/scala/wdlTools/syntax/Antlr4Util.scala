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
  def getTextSource(startToken: Token, maybeStopToken: Option[Token] = None): TextSource = {
    val stopToken = maybeStopToken.getOrElse(startToken)
    syntax.TextSource(
        line = startToken.getLine,
        col = startToken.getCharPositionInLine,
        endLine = stopToken.getLine,
        endCol = stopToken.getCharPositionInLine + stopToken.getText.length
    )
  }

  def getTextSource(ctx: ParserRuleContext): TextSource = {
    getTextSource(ctx.getStart, Some(ctx.getStop))
  }

  def getTextSource(symbol: TerminalNode): TextSource = {
    getTextSource(symbol.getSymbol, None)
  }

  case class Grammar[L <: Lexer, P <: Parser](lexer: L,
                                              parser: P,
                                              errListener: ErrorListener,
                                              commentChannelName: String,
                                              docSourceURL: Option[URL] = None,
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

    def getComment(ctx: ParserRuleContext, before: Boolean = true): Option[Comment] = {
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
          val comments: mutable.Buffer[Comment] = mutable.ArrayBuffer.empty
          val currentComment: mutable.Buffer[String] = mutable.ArrayBuffer.empty
          var preformatted: Boolean = false
          val lines = commentTokens.asScala.map(_.getText).toVector
          lines.foreach { line =>
            if (line.startsWith("##")) {
              // handle pre-formatted comment line
              if (!preformatted) {
                if (currentComment.nonEmpty) {
                  comments.append(CommentLine(currentComment.mkString(" ")))
                  currentComment.clear()
                }
                preformatted = true
              }
              currentComment.append(line.substring(2).trim)
            } else {
              // handle regular comment line
              val trimmed = line.substring(1).trim
              if (preformatted) {
                if (currentComment.nonEmpty) {
                  comments.append(CommentPreformatted(currentComment.toVector))
                  currentComment.clear()
                }
                preformatted = false
              }
              if (trimmed.isEmpty) {
                if (currentComment.nonEmpty) {
                  comments.append(CommentLine(currentComment.mkString(" ")))
                  currentComment.clear()
                }
                comments.append(CommentEmpty())
              } else {
                currentComment.append(trimmed)
              }
            }
          }
          if (currentComment.nonEmpty) {
            // handle final comment line
            if (preformatted) {
              comments.append(CommentPreformatted(currentComment.toVector))
            } else {
              comments.append(CommentLine(currentComment.mkString(" ")))
            }
          }
          return Some(if (comments.size > 1) {
            CommentCompound(comments.toVector)
          } else {
            comments.head
          })
        }
      }
      None
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

      Grammar(lexer, parser, errListener, commentChannelName, docSourceUrl, opts)
    }

    def createLexer(charStream: CharStream): L

    def createParser(tokenStream: CommonTokenStream): P
  }
}
