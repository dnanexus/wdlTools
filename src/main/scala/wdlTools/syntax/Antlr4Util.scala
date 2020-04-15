package wdlTools.syntax

import java.net.URL
import java.nio.ByteBuffer

import org.antlr.v4.runtime.tree.TerminalNode

import collection.JavaConverters._
import org.antlr.v4.runtime.{
  BaseErrorListener,
  BufferedTokenStream,
  CharStream,
  CodePointBuffer,
  CodePointCharStream,
  CommonTokenStream,
  Lexer,
  Parser,
  ParserRuleContext,
  RecognitionException,
  Recognizer,
  Token
}
import wdlTools.syntax
import wdlTools.util.{Options, SourceCode, Verbosity}

import scala.collection.mutable

object Antlr4Util {
  val AllKey: String = "*"

  def makeWdlException(msg: String,
                       ctx: ParserRuleContext,
                       docSourceURL: Option[URL] = None): RuntimeException = {
    val src = getSourceText(ctx, docSourceURL)
    new RuntimeException(s"${msg} ${src}")
  }

  def getSourceText(ctx: ParserRuleContext, docSourceURL: Option[URL]): TextSource = {
    getSourceText(ctx.start, docSourceURL)
  }

  def getSourceText(symbol: TerminalNode, docSourceURL: Option[URL]): TextSource = {
    getSourceText(symbol.getSymbol, docSourceURL)
  }

  def getSourceText(token: Token, docSourceURL: Option[URL]): TextSource = {
    syntax.TextSource(line = token.getLine, col = token.getCharPositionInLine, url = docSourceURL)
  }

  trait Antlr4ParserListener {
    def notify(grammar: Grammar[Lexer, Parser], ctx: ParserRuleContext): Unit
  }

  case class SyntaxError(symbol: String, line: Int, charPositionInLine: Int, msg: String)

  case class ErrorListener(conf: Options) extends BaseErrorListener {
    var errors = Vector.empty[SyntaxError]

    override def syntaxError(recognizer: Recognizer[_, _],
                             offendingSymbol: Any,
                             line: Int,
                             charPositionInLine: Int,
                             msg: String,
                             e: RecognitionException): Unit = {
      val symbolText =
        offendingSymbol match {
          case tok: Token =>
            tok.getText
          case _ =>
            offendingSymbol.toString
        }
      val err = SyntaxError(symbolText, line, charPositionInLine, msg)
      errors = errors :+ err
    }

    def getAllErrors: Vector[SyntaxError] = errors
  }

  case class Grammar[L <: Lexer, P <: Parser](
      lexer: L,
      parser: P,
      parserListenerKeys: Vector[String],
      parserListeners: Map[String, Vector[Antlr4ParserListener]] = Map.empty,
      errListener: ErrorListener,
      commentChannelName: String,
      docSourceUrl: Option[URL] = None,
      opts: Options
  ) {
    def getSourceText(ctx: ParserRuleContext): TextSource = {
      Antlr4Util.getSourceText(ctx, docSourceUrl)
    }

    def getSourceText(symbol: TerminalNode): TextSource = {
      Antlr4Util.getSourceText(symbol, docSourceUrl)
    }

    def getChannel(name: String): Int = {
      val channel = lexer.getChannelNames.indexOf(name)
      require(channel >= 0)
      channel
    }

    val hiddenChannel: Int = getChannel("HIDDEN")
    val commentChannel: Int = getChannel(commentChannelName)

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

    def notifyParserListeners(ctx: ParserRuleContext): Unit = {
      val keys = Vector(parserListenerKeys(ctx.getRuleIndex), AllKey)
      keys.foreach { key =>
        if (parserListeners.nonEmpty && parserListeners.contains(key)) {
          parserListeners(key).foreach { listener =>
            listener.notify(this.asInstanceOf[Grammar[Lexer, Parser]], ctx)
          }
        }
      }
    }

    def getHiddenTokens(ctx: ParserRuleContext,
                        channel: Int = hiddenChannel,
                        before: Boolean = true): Vector[Token] = {
      val start = ctx.getStart
      val idx = start.getTokenIndex
      if (idx >= 0) {
        val tokenStream = parser.getTokenStream.asInstanceOf[BufferedTokenStream]
        val tokens = if (before) {
          tokenStream.getHiddenTokensToLeft(idx, channel)
        } else {
          tokenStream.getHiddenTokensToRight(idx, channel)
        }
        if (tokens != null) {
          return tokens.asScala.toVector
        }
      }
      Vector.empty
    }

    def getComment(ctx: ParserRuleContext, before: Boolean = true): Option[Comment] = {
      val commentTokens = getHiddenTokens(ctx, commentChannel, before)
      if (commentTokens.nonEmpty) {
        val comments: mutable.Buffer[Comment] = mutable.ArrayBuffer.empty
        val currentComment: mutable.Buffer[String] = mutable.ArrayBuffer.empty
        var preformatted: Boolean = false
        val lines = commentTokens.map(_.getText)
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
      None
    }
  }

  abstract class GrammarFactory[L <: Lexer, P <: Parser](
      opts: Options,
      commentChannelName: String = "COMMENTS"
  ) {

    private val parserListeners: mutable.Map[String, mutable.Buffer[Antlr4ParserListener]] =
      mutable.HashMap.empty

    def parserListenerKeys: Vector[String]

    def addParserListener(
        listener: Antlr4ParserListener,
        keys: Vector[String]
    ): Unit = {
      keys.foreach { key =>
        if (!parserListeners.contains(key)) {
          parserListeners(key) = mutable.ArrayBuffer.empty
        }
        parserListeners(key).append(
            listener.asInstanceOf[Antlr4ParserListener]
        )
      }
    }

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

      Grammar(lexer, parser, parserListenerKeys, parserListeners.toMap.map {
        case (k, v) => k -> v.toVector
      }, errListener, commentChannelName, docSourceUrl, opts)
    }

    def createLexer(charStream: CharStream): L

    def createParser(tokenStream: CommonTokenStream): P
  }
}
