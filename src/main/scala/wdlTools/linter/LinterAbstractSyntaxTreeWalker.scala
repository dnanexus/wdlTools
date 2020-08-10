package wdlTools.linter

import wdlTools.syntax.{AbstractSyntaxTreeVisitor, AbstractSyntaxTreeWalker, VisitorContext}
import wdlTools.syntax.AbstractSyntax._

case class LinterAbstractSyntaxTreeWalker(visitors: Vector[AbstractSyntaxTreeVisitor],
                                          followImports: Boolean = true)
    extends AbstractSyntaxTreeWalker(followImports) {
  def visitEveryElement(ctx: VisitorContext[Element]): Unit = {}

  override def visitName[P <: Element](name: String, parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitName(name, parent))
    super.visitName(name, parent)
  }

  override def visitKey[P <: Element](key: String, parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitKey(key, parent))
    super.visitKey(key, parent)
  }

  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDocument(ctx))
    super.visitDocument(ctx)
  }

  override def visitVersion(ctx: VisitorContext[Version]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitVersion(ctx))
    super.visitVersion(ctx)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportAlias(ctx))
    super.visitImportAlias(ctx)
  }

  override def visitImportName(name: String, parent: VisitorContext[ImportDoc]): Unit = {
    visitors.foreach(_.visitImportName(name, parent))
    super.visitImportName(name, parent)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportDoc(ctx))
    super.visitImportDoc(ctx)
  }

  override def visitDataType(ctx: VisitorContext[Type]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDataType(ctx))
    super.visitDataType(ctx)
  }

  override def visitStructMember(ctx: VisitorContext[StructMember]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitStructMember(ctx))
    super.visitStructMember(ctx)
  }

  override def visitStruct(ctx: VisitorContext[TypeStruct]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitStruct(ctx))
    super.visitStruct(ctx)
  }

  override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitExpression(ctx))
    super.visitExpression(ctx)
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDeclaration(ctx))
    super.visitDeclaration(ctx)
  }

  override def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitInputSection(ctx))
    super.visitInputSection(ctx)
  }

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCommandSection(ctx))
    super.visitCommandSection(ctx)
  }

  override def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitOutputSection(ctx))
    super.visitOutputSection(ctx)
  }

  override def visitRuntimeKV(ctx: VisitorContext[RuntimeKV]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeKV(ctx))
    super.visitRuntimeKV(ctx)
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeSection(ctx))
    super.visitRuntimeSection(ctx)
  }

  override def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaValue(ctx))
    super.visitMetaValue(ctx)
  }

  override def visitMetaKV(ctx: VisitorContext[MetaKV]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaKV(ctx))
    super.visitMetaKV(ctx)
  }

  override def visitHintsSection(ctx: VisitorContext[HintsSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitHintsSection(ctx))
    super.visitHintsSection(ctx)
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaSection(ctx))
    super.visitMetaSection(ctx)
  }

  override def visitParameterMetaSection(
      ctx: VisitorContext[ParameterMetaSection]
  ): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitParameterMetaSection(ctx))
    super.visitParameterMetaSection(ctx)
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitTask(ctx))
    super.visitTask(ctx)
  }

  override def visitCallName(name: String,
                             alias: Option[String],
                             parent: VisitorContext[Call]): Unit = {
    visitors.foreach(_.visitCallName(name, alias, parent))
    super.visitCallName(name, alias, parent)
  }

  override def visitCallInput(ctx: VisitorContext[CallInput]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCallInput(ctx))
    super.visitCallInput(ctx)
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCall(ctx))
    super.visitCall(ctx)
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitScatter(ctx))
    super.visitScatter(ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitConditional(ctx))
    super.visitConditional(ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitBody(body, parent))
    super.visitBody(body, parent)
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
    visitEveryElement(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitWorkflow(ctx))
    super.visitWorkflow(ctx)
  }
}
