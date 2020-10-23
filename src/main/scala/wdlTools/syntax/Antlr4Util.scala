package wdlTools.syntax

import org.antlr.v4.runtime.tree.{ParseTreeListener, TerminalNode}
import org.antlr.v4.runtime.{
  BaseErrorListener,
  BufferedTokenStream,
  Lexer,
  Parser,
  ParserRuleContext,
  RecognitionException,
  Recognizer,
  Token
}

import scala.jdk.CollectionConverters._
import wdlTools.syntax
import dx.util.{FileNode, Logger}

object Antlr4Util {
  private var parserTrace: Boolean = false

  def setParserTrace(trace: Boolean): Boolean = {
    val currentTrace = parserTrace
    parserTrace = trace
    currentTrace
  }

  def getSourceLocation(source: FileNode,
                        startToken: Token,
                        maybeStopToken: Option[Token] = None): SourceLocation = {
    // TODO: for an ending token that containing newlines, the endLine and endCol will be wrong
    val stopToken = maybeStopToken.getOrElse(startToken)
    syntax.SourceLocation(
        source = source,
        line = startToken.getLine,
        col = startToken.getCharPositionInLine,
        endLine = stopToken.getLine,
        endCol = stopToken.getCharPositionInLine + stopToken.getText.length
    )
  }

  def getSourceLocation(source: FileNode, ctx: ParserRuleContext): SourceLocation = {
    val stop = ctx.getStop
    getSourceLocation(source, ctx.getStart, Option(stop))
  }

  def getSourceLocation(source: FileNode, symbol: TerminalNode): SourceLocation = {
    getSourceLocation(source, symbol.getSymbol, None)
  }

  // Based on Patrick Magee's error handling code (https://github.com/patmagee/wdl4j)
  //
  case class WdlAggregatingErrorListener(docSource: FileNode) extends BaseErrorListener {

    private var errors = Vector.empty[SyntaxError]

    // This is called by the antlr grammar during parsing.
    // We collect these errors in a list, and report collectively
    // when parsing is complete.
    override def syntaxError(recognizer: Recognizer[_, _],
                             offendingSymbol: Any,
                             line: Int,
                             charPositionInLine: Int,
                             msg: String,
                             e: RecognitionException): Unit = {
      val symbolText =
        offendingSymbol match {
          case null => ""
          case tok: Token =>
            tok.getText
          case _ =>
            offendingSymbol.toString
        }
      val err = SyntaxError(
          symbolText,
          SourceLocation(docSource, line, charPositionInLine, line, charPositionInLine),
          msg
      )
      errors = errors :+ err
    }

    def getErrors: Vector[SyntaxError] = errors

    def hasErrors: Boolean = errors.nonEmpty
  }

  private case class CommentListener(docSource: FileNode,
                                     tokenStream: BufferedTokenStream,
                                     channelIndex: Int)
      extends AllParseTreeListener {
    private var comments: Map[Int, Comment] = Map.empty

    def getComments: CommentMap = CommentMap(comments)

    def addComments(tokens: Vector[Token]): Unit = {
      tokens.foreach { tok =>
        val source = Antlr4Util.getSourceLocation(docSource, tok, None)
        if (comments.contains(source.line)) {
          // TODO: should this be an error?
        } else {
          comments += (source.line -> Comment(tok.getText, source))
        }
      }
    }

    override def exitEveryRule(ctx: ParserRuleContext): Unit = {
      // full-line comments
      if (ctx.getStart != null && ctx.getStart.getTokenIndex >= 0) {
        val beforeComments =
          tokenStream.getHiddenTokensToLeft(ctx.getStart.getTokenIndex, channelIndex)
        if (beforeComments != null) {
          addComments(beforeComments.asScala.toVector)
        }
      }
      // line-end comments
      if (ctx.getStop != null && ctx.getStop.getTokenIndex >= 0) {
        val afterComments =
          tokenStream.getHiddenTokensToRight(ctx.getStop.getTokenIndex, channelIndex)
        if (afterComments != null) {
          addComments(afterComments.asScala.toVector)
        }
      }
    }
  }

  trait ParseTreeListenerFactory {
    def createParseTreeListeners(grammar: Grammar): Vector[ParseTreeListener]
  }

  class Grammar(
      val version: WdlVersion,
      val lexer: Lexer,
      val parser: Parser,
      val listenerFactories: Vector[ParseTreeListenerFactory],
      val docSource: FileNode,
      val logger: Logger = Logger.get
  ) {
    val errListener: WdlAggregatingErrorListener = WdlAggregatingErrorListener(docSource)
    // setting up our own error handling
    lexer.removeErrorListeners()
    lexer.addErrorListener(errListener)
    parser.removeErrorListeners()
    parser.addErrorListener(errListener)

    if (parserTrace) {
      parser.setTrace(true)
    }

    def getChannel(name: String): Int = {
      val channel = lexer.getChannelNames.indexOf(name)
      require(channel >= 0)
      channel
    }

    val hiddenChannel: Int = getChannel("HIDDEN")
    val commentChannel: Int = getChannel("COMMENTS")
    private val commentListener = CommentListener(
        docSource,
        parser.getTokenStream.asInstanceOf[BufferedTokenStream],
        commentChannel
    )

    parser.addParseListener(commentListener)
    listenerFactories.foreach(
        _.createParseTreeListeners(this).map(parser.addParseListener)
    )

    def getHiddenTokens(ctx: ParserRuleContext,
                        channel: Int = hiddenChannel,
                        before: Boolean = true,
                        within: Boolean = false,
                        after: Boolean = false): Vector[Token] = {

      def getTokenIndex(tok: Token): Option[Int] = {
        if (tok == null || tok.getTokenIndex < 0) {
          None
        } else {
          Some(tok.getTokenIndex)
        }
      }

      val startIdx = if (before || within) getTokenIndex(ctx.getStart) else None
      val stopIdx = if (after || within) getTokenIndex(ctx.getStop) else None

      if (startIdx.isEmpty && stopIdx.isEmpty) {
        Vector.empty
      } else {
        def tokensToSet(tokens: java.util.List[Token]): Set[Token] = {
          if (tokens == null) {
            Set.empty
          } else {
            tokens.asScala.toSet
          }
        }

        val tokenStream = parser.getTokenStream.asInstanceOf[BufferedTokenStream]
        val beforeTokens = if (before && startIdx.isDefined) {
          tokensToSet(tokenStream.getHiddenTokensToLeft(startIdx.get, channel))
        } else {
          Set.empty
        }
        val withinTokens = if (within && startIdx.isDefined && stopIdx.isDefined) {
          (startIdx.get until stopIdx.get)
            .flatMap { idx =>
              tokensToSet(tokenStream.getHiddenTokensToRight(idx, channel))
            }
            .toSet
            .filter(tok => tok.getTokenIndex >= startIdx.get && tok.getTokenIndex <= stopIdx.get)
        } else {
          Set.empty
        }
        val afterTokens = if (after && stopIdx.isDefined) {
          tokensToSet(tokenStream.getHiddenTokensToRight(stopIdx.get, channel))
        } else {
          Set.empty
        }
        (beforeTokens ++ withinTokens ++ afterTokens).toVector.sortWith((left, right) =>
          left.getTokenIndex < right.getTokenIndex
        )
      }
    }

    def beforeParse(): Unit = {
      // call the enter() method on any `AllParseTreeListener`s
      parser.getParseListeners.asScala.foreach {
        case l: AllParseTreeListener => l.enter()
        case _                       => ()
      }
    }

    def afterParse(): Unit = {
      // call the exit() method on any `AllParseTreeListener`s
      parser.getParseListeners.asScala.foreach {
        case l: AllParseTreeListener => l.exit()
        case _                       => ()
      }

      // check if any errors were found
      val errors: Vector[SyntaxError] = errListener.getErrors
      if (errors.nonEmpty) {
        if (!logger.quiet) {
          errors.foreach(err => logger.warning(err.toString))
        }
        throw new SyntaxException(errors)
      }
    }

    def visitDocument[T <: ParserRuleContext, E](ctx: T, visitor: (T, CommentMap) => E): E = {
      if (ctx == null) {
        throw new Exception("WDL file does not contain a valid document")
      }
      beforeParse()
      val result = visitor(ctx, commentListener.getComments)
      afterParse()
      result
    }

    def visitFragment[T <: ParserRuleContext, E](
        ctx: T,
        visitor: T => E
    ): E = {
      if (ctx == null) {
        throw new Exception("Not a valid fragment")
      }
      beforeParse()
      val result = visitor(ctx)
      afterParse()
      result

    }
  }
}
