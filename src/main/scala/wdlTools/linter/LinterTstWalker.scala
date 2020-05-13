package wdlTools.linter

import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.{TypedSyntaxTreeVisitor, TypedSyntaxTreeWalker}
import wdlTools.util.Options

case class LinterTstWalker(opts: Options, visitors: Vector[TypedSyntaxTreeVisitor])
    extends TypedSyntaxTreeWalker(opts) {
  def visitEveryRule(ctx: VisitorContext[Element]): Unit = {}

  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDocument(ctx))
    super.visitDocument(ctx)
  }

  override def visitIdentifier[P <: Element](identifier: String,
                                             parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitIdentifier(identifier, parent))
    super.visitIdentifier(identifier, parent)
  }

  override def visitVersion(ctx: VisitorContext[Version]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitVersion(ctx))
    super.visitVersion(ctx)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportAlias(ctx))
    super.visitImportAlias(ctx)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportDoc(ctx))
    super.visitImportDoc(ctx)
  }

  override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitExpression(ctx))
    super.visitExpression(ctx)
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDeclaration(ctx))
    super.visitDeclaration(ctx)
  }

  override def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitInputSection(ctx))
    super.visitInputSection(ctx)
  }

  override def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitOutputSection(ctx))
    super.visitOutputSection(ctx)
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCall(ctx))
    super.visitCall(ctx)
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitScatter(ctx))
    super.visitScatter(ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitConditional(ctx))
    super.visitConditional(ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       ctx: VisitorContext[P]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitBody(body, ctx))
    super.visitBody(body, ctx)
  }

  override def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaValue(ctx))
    super.visitMetaValue(ctx)
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaSection(ctx))
    super.visitMetaSection(ctx)
  }

  override def visitParameterMetaSection(
      ctx: VisitorContext[ParameterMetaSection]
  ): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitParameterMetaSection(ctx))
    super.visitParameterMetaSection(ctx)
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitWorkflow(ctx))
    super.visitWorkflow(ctx)
  }

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCommandSection(ctx))
    super.visitCommandSection(ctx)
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeSection(ctx))
    super.visitRuntimeSection(ctx)
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    visitEveryRule(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitTask(ctx))
    super.visitTask(ctx)
  }
}
