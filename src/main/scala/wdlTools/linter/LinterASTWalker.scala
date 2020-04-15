package wdlTools.linter

import wdlTools.syntax.{ASTVisitor, ASTWalker, AbstractSyntax}

class LinterASTWalker(visitors: Vector[ASTVisitor], walkImports: Boolean = false)
    extends ASTWalker(walkImports = walkImports) {
  override def visitDocument(ctx: ASTVisitor.Context[AbstractSyntax.Document]): Unit = {
    visitors.foreach(_.visitDocument(ctx))
    super.visitDocument(ctx)
  }

  override def visitIdentifier[P <: AbstractSyntax.Element](identifier: String,
                                                            parent: ASTVisitor.Context[P]): Unit = {
    visitors.foreach(_.visitIdentifier(identifier, parent))
    super.visitIdentifier(identifier, parent)
  }

  override def visitVersion(ctx: ASTVisitor.Context[AbstractSyntax.Version]): Unit = {
    visitors.foreach(_.visitVersion(ctx))
    super.visitVersion(ctx)
  }

  override def visitImportAlias(ctx: ASTVisitor.Context[AbstractSyntax.ImportAlias]): Unit = {
    visitors.foreach(_.visitImportAlias(ctx))
    super.visitImportAlias(ctx)
  }

  override def visitImportDoc(ctx: ASTVisitor.Context[AbstractSyntax.ImportDoc]): Unit = {
    visitors.foreach(_.visitImportDoc(ctx))
    super.visitImportDoc(ctx)
  }

  override def visitStruct(ctx: ASTVisitor.Context[AbstractSyntax.TypeStruct]): Unit = {
    visitors.foreach(_.visitStruct(ctx))
    super.visitStruct(ctx)
  }

  override def visitDataType(ctx: ASTVisitor.Context[AbstractSyntax.Type]): Unit = {
    visitors.foreach(_.visitDataType(ctx))
    super.visitDataType(ctx)
  }

  override def visitStructMember(ctx: ASTVisitor.Context[AbstractSyntax.StructMember]): Unit = {
    visitors.foreach(_.visitStructMember(ctx))
    super.visitStructMember(ctx)
  }

  override def visitExpression(expr: ASTVisitor.Context[AbstractSyntax.Expr]): Unit = {
    visitors.foreach(_.visitExpression(expr))
    super.visitExpression(expr)
  }

  override def visitDeclaration(ctx: ASTVisitor.Context[AbstractSyntax.Declaration]): Unit = {
    visitors.foreach(_.visitDeclaration(ctx))
    super.visitDeclaration(ctx)
  }

  override def visitInputSection(ctx: ASTVisitor.Context[AbstractSyntax.InputSection]): Unit = {
    visitors.foreach(_.visitInputSection(ctx))
    super.visitInputSection(ctx)
  }

  override def visitOutputSection(ctx: ASTVisitor.Context[AbstractSyntax.OutputSection]): Unit = {
    visitors.foreach(_.visitOutputSection(ctx))
    super.visitOutputSection(ctx)
  }

  override def visitCallAlias(ctx: ASTVisitor.Context[AbstractSyntax.CallAlias]): Unit = {
    visitors.foreach(_.visitCallAlias(ctx))
    super.visitCallAlias(ctx)
  }

  override def visitCallInput(ctx: ASTVisitor.Context[AbstractSyntax.CallInput]): Unit = {
    visitors.foreach(_.visitCallInput(ctx))
    super.visitCallInput(ctx)
  }

  override def visitCall(ctx: ASTVisitor.Context[AbstractSyntax.Call]): Unit = {
    visitors.foreach(_.visitCall(ctx))
    super.visitCall(ctx)
  }

  override def visitScatter(ctx: ASTVisitor.Context[AbstractSyntax.Scatter]): Unit = {
    visitors.foreach(_.visitScatter(ctx))
    super.visitScatter(ctx)
  }

  override def visitConditional(ctx: ASTVisitor.Context[AbstractSyntax.Conditional]): Unit = {
    visitors.foreach(_.visitConditional(ctx))
    super.visitConditional(ctx)
  }

  override def visitBody[P <: AbstractSyntax.Element](body: Vector[AbstractSyntax.WorkflowElement],
                                                      ctx: ASTVisitor.Context[P]): Unit = {
    visitors.foreach(_.visitBody(body, ctx))
    super.visitBody(body, ctx)
  }

  override def visitMetaKV(ctx: ASTVisitor.Context[AbstractSyntax.MetaKV]): Unit = {
    visitors.foreach(_.visitMetaKV(ctx))
    super.visitMetaKV(ctx)
  }

  override def visitMetaSection(ctx: ASTVisitor.Context[AbstractSyntax.MetaSection]): Unit = {
    visitors.foreach(_.visitMetaSection(ctx))
    super.visitMetaSection(ctx)
  }

  override def visitParameterMetaSection(
      ctx: ASTVisitor.Context[AbstractSyntax.ParameterMetaSection]
  ): Unit = {
    visitors.foreach(_.visitParameterMetaSection(ctx))
    super.visitParameterMetaSection(ctx)
  }

  override def visitWorkflow(ctx: ASTVisitor.Context[AbstractSyntax.Workflow]): Unit = {
    visitors.foreach(_.visitWorkflow(ctx))
    super.visitWorkflow(ctx)
  }

  override def visitCommandSection(ctx: ASTVisitor.Context[AbstractSyntax.CommandSection]): Unit = {
    visitors.foreach(_.visitCommandSection(ctx))
    super.visitCommandSection(ctx)
  }

  override def visitRuntimeKV(ctx: ASTVisitor.Context[AbstractSyntax.RuntimeKV]): Unit = {
    visitors.foreach(_.visitRuntimeKV(ctx))
    super.visitRuntimeKV(ctx)
  }

  override def visitRuntimeSection(ctx: ASTVisitor.Context[AbstractSyntax.RuntimeSection]): Unit = {
    visitors.foreach(_.visitRuntimeSection(ctx))
    super.visitRuntimeSection(ctx)
  }

  override def visitTask(ctx: ASTVisitor.Context[AbstractSyntax.Task]): Unit = {
    visitors.foreach(_.visitTask(ctx))
    super.visitTask(ctx)
  }
}
