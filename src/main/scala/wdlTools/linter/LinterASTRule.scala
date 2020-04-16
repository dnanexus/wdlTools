package wdlTools.linter

import wdlTools.syntax.AbstractSyntax.Element
import wdlTools.syntax.ASTVisitor

import scala.collection.mutable

class LinterASTRule(val id: String, errors: mutable.Buffer[LinterError]) extends ASTVisitor {
  protected def addError(element: Element): Unit = {
    errors.append(LinterError(id, element.text))
  }
}
