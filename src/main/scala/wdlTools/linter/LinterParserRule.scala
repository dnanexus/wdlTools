package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.{Lexer, Parser, Token}
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.openwdl.wdl.parser.v1_0.WdlV1ParserBaseListener
import wdlTools.syntax.Antlr4Util
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory

import scala.collection.mutable
import scala.reflect.ClassTag

class LinterParserRule(val id: String, errors: mutable.Buffer[LinterError])
    extends WdlV1ParserBaseListener {
  protected def addError(tok: Token, docSourceUrl: Option[URL] = None): Unit = {
    errors.append(LinterError(id, Antlr4Util.getSourceText(tok, docSourceUrl)))
  }
}

case class LinterParserRuleFactory[R <: LinterParserRule](errors: mutable.Buffer[LinterError])(
    implicit tag: ClassTag[R]
) extends ParseTreeListenerFactory {
  override def createParseTreeListener(
      grammar: Antlr4Util.Grammar[Lexer, Parser]
  ): ParseTreeListener = {
    tag.runtimeClass.getDeclaredConstructors.head.newInstance(grammar, errors).asInstanceOf[R]
  }
}
