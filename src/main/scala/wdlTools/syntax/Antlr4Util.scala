package wdlTools.syntax

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
  ParserRuleContext
}
import wdlTools.syntax
import wdlTools.util.{Options, URL, Verbosity}

import scala.collection.mutable

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

    def getSourceText(ctx: ParserRuleContext, docSourceURL: URL): TextSource = {
      val tok = ctx.start
      val line = tok.getLine
      val col = tok.getCharPositionInLine
      syntax.TextSource(line = line, col = col, url = docSourceURL)
    }

    def getSourceText(symbol: TerminalNode, docSourceURL: URL): TextSource = {
      val tok = symbol.getSymbol
      syntax.TextSource(line = tok.getLine, col = tok.getCharPositionInLine, url = docSourceURL)
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
