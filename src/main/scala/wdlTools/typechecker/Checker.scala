package wdlTools.typechecker

import wdlTools.util.Util.Conf
import wdlTools.syntax.{AbstractSyntax => AST}

case class Checker(conf : Conf) {

  type Context = Map[String, AST.Type]

  // check if the right hand side of an assignment matches the left hand side
  //
  // Negative examples:
  //    Int i = "hello"
  //    Array[File] files = "8"
  //
  // Positive examples:
  //    Int k =  3 + 9
  //    Int j = k * 3
  //    String s = "Ford model T"
  //    String s2 = 5
  private def tMatch(left : AST.Type,
                     right : AST.Type) : Boolean = ???

  private def typeEval(expr : AST.Expr,
                       ctx : Context) : AST.Type = ???

  // Examples:
  //   Int x
  //   Int x = 5
  //   Int x = 7 + y
  def apply(decl : AST.Declaration, ctx : Context) : Boolean = {
    decl.expr match {
      case None =>
        true
      case Some(expr) =>
        val rhsType : AST.Type = typeEval(expr)
        tMatch(decl.wdlType, rhsType)
    }
  }

  def apply(inputSection : AST.InputSection, ctx : Context) : Boolean = {
    val cDecl = inputSection.declarations.map(apply)
    cDecl.forall(_ == true)
  }

  def apply(outputSection : AST.OutputSection, ctx : Context) : Boolean = {
    val cDecl = outputSection.declarations.map(apply)
    cDecl.forall(_ == true)
  }

  // TASK
  //
  // - An inputs type has to match the type of its default value (if any)
  // - Check the declarations
  // - Assignments to an output variable must match
  //
  // We can't check the validity of the command section.
  def apply(task : AST.Task, ctx : Context) : Boolean = {
    val cIn = task.input.map(apply).getOrElse(true)
    if (!cIn)
      return false
    val cOut = task.output.map(apply).getOrElse(true)
    if (!cOut)
      return false
    return true
  }

  // DOCUMENT
  //
  // check if the WDL document is correctly typed
  def apply(doc : AST.Document) : Boolean = {
    val vec : Vector[Boolean] = doc.elements.map{
      case task : AST.Task =>
        apply(task)

      case importDoc : AST.ImportDoc =>
        // don't go into imports right now
        true

      case struct : AST.TypeStruct =>
        // TODO: record the struct, because we are going to need it
        // for the rest of the typechecking.
        true
    }

    // a single type error suffices to declare
    // the entire document wrong
    if (vec.exists(_ == false))
      return false

    // TODO
    // doc.workflow

    return true
  }
}
