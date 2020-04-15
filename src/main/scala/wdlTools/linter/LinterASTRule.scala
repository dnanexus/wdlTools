package wdlTools.linter

import wdlTools.syntax.ASTVisitor

import scala.collection.mutable

class LinterASTRule(val id: String, errors: mutable.Buffer[LinterError]) extends ASTVisitor {}
