package wdlTools.linter

import wdlTools.syntax.AbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.syntax.{AbstractSyntaxTreeVisitor, AbstractSyntaxTreeWalker}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.Options

case class LinterAbstractSyntaxTreeWalker(opts: Options,
                                          visitors: Vector[AbstractSyntaxTreeVisitor])
    extends AbstractSyntaxTreeWalker(opts) {
  def visitEveryContext(ctx: VisitorContext[Element]): Unit = {}

  override def visitName[P <: Element](name: String, parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitName(name, parent))
    super.visitName(name, parent)
  }

  override def visitKey[P <: Element](key: String, parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitKey(key, parent))
    super.visitKey(key, parent)
  }

  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDocument(ctx))
    super.visitDocument(ctx)
  }

  override def visitVersion(ctx: VisitorContext[Version]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitVersion(ctx))
    super.visitVersion(ctx)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportAlias(ctx))
    super.visitImportAlias(ctx)
  }

  override def visitImportName(name: String, parent: VisitorContext[ImportDoc]): Unit = {
    visitors.foreach(_.visitImportName(name, parent))
    super.visitImportName(name, parent)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitImportDoc(ctx))
    super.visitImportDoc(ctx)
  }

  override def visitDataType(ctx: VisitorContext[Type]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDataType(ctx))
    super.visitDataType(ctx)
  }

  override def visitStruct(ctx: VisitorContext[TypeStruct]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitStruct(ctx))
    super.visitStruct(ctx)
  }

  override def visitStructMember(ctx: VisitorContext[StructMember]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitStructMember(ctx))
    super.visitStructMember(ctx)
  }

  override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitExpression(ctx))
    super.visitExpression(ctx)
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitDeclaration(ctx))
    super.visitDeclaration(ctx)
  }

  override def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitInputSection(ctx))
    super.visitInputSection(ctx)
  }

  override def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitOutputSection(ctx))
    super.visitOutputSection(ctx)
  }

  override def visitCallName(name: String,
                             alias: Option[String],
                             parent: VisitorContext[Call]): Unit = {
    visitors.foreach(_.visitCallName(name, alias, parent))
    super.visitCallName(name, alias, parent)
  }

  override def visitCallInput(ctx: VisitorContext[CallInput]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCallInput(ctx))
    super.visitCallInput(ctx)
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCall(ctx))
    super.visitCall(ctx)
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitScatter(ctx))
    super.visitScatter(ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitConditional(ctx))
    super.visitConditional(ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       parent: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitBody(body, parent))
    super.visitBody(body, parent)
  }

  override def visitMetaKV(ctx: VisitorContext[MetaKV]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaKV(ctx))
    super.visitMetaKV(ctx)
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaSection(ctx))
    super.visitMetaSection(ctx)
  }

  override def visitParameterMetaSection(
      ctx: VisitorContext[ParameterMetaSection]
  ): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitParameterMetaSection(ctx))
    super.visitParameterMetaSection(ctx)
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitWorkflow(ctx))
    super.visitWorkflow(ctx)
  }

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitCommandSection(ctx))
    super.visitCommandSection(ctx)
  }

  override def visitRuntimeKV(ctx: VisitorContext[RuntimeKV]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeKV(ctx))
    super.visitRuntimeKV(ctx)
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeSection(ctx))
    super.visitRuntimeSection(ctx)
  }

  override def visitHintsKV(ctx: VisitorContext[HintsKV]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitHintsKV(ctx))
    super.visitHintsKV(ctx)
  }

  override def visitHintsSection(ctx: VisitorContext[HintsSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitHintsSection(ctx))
    super.visitHintsSection(ctx)
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitTask(ctx))
    super.visitTask(ctx)
  }
}
