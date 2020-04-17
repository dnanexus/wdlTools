package wdlTools.linter

import java.net.URL

import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.{Lexer, Parser, Token}
import wdlTools.syntax.AbstractSyntax.Element
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{ASTVisitor, AllParseTreeListener, Antlr4Util, TextSource}

import scala.collection.mutable
import scala.reflect.ClassTag

case class LinterError(ruleId: String, textSource: TextSource)

// ideally we could provide the id as a class annotation, but dealing with annotations
// in Scala is currently horrendous - for how it would be done, see
// https://stackoverflow.com/questions/23046958/accessing-an-annotation-value-in-scala

class LinterParserRule(id: String, errors: mutable.Buffer[LinterError])
    extends AllParseTreeListener {
  protected def addError(tok: Token, docSourceUrl: Option[URL] = None): Unit = {
    errors.append(LinterError(id, Antlr4Util.getSourceText(tok, docSourceUrl)))
  }
}

case class LinterParserRuleFactory[R <: LinterParserRule](
    id: String,
    errors: mutable.Buffer[LinterError]
)(
    implicit tag: ClassTag[R]
) extends ParseTreeListenerFactory {
  override def createParseTreeListener(
      grammar: Antlr4Util.Grammar[Lexer, Parser]
  ): ParseTreeListener = {
    val constructor = tag.runtimeClass.getDeclaredConstructors.head
    constructor.newInstance(id, errors, grammar).asInstanceOf[R]
  }
}

class LinterASTRule(val id: String, errors: mutable.Buffer[LinterError]) extends ASTVisitor {
  protected def addError(element: Element): Unit = {
    errors.append(LinterError(id, element.text))
  }
}
