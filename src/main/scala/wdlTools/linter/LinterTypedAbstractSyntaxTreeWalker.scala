package wdlTools.linter

import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.{TypedSyntaxTreeVisitor, TypedSyntaxTreeWalker}
import wdlTools.util.Options

case class LinterTypedAbstractSyntaxTreeWalker(opts: Options,
                                               visitors: Vector[TypedSyntaxTreeVisitor])
    extends TypedSyntaxTreeWalker(opts) {
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

  override def visitStruct(ctx: VisitorContext[StructDefinition]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitStruct(ctx))
    super.visitStruct(ctx)
  }

  override def visitStructMember(name: String,
                                 wdlType: WdlType,
                                 parent: VisitorContext[StructDefinition]): Unit = {
    visitors.foreach(_.visitStructMember(name, wdlType, parent))
    super.visitStructMember(name, wdlType, parent)
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

  override def visitCallName(actualName: String,
                             fullyQualifiedName: String,
                             alias: Option[String],
                             parent: VisitorContext[Call]): Unit = {
    visitors.foreach(_.visitCallName(actualName, fullyQualifiedName, alias, parent))
    super.visitCallName(actualName, fullyQualifiedName, alias, parent)
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
                                       ctx: VisitorContext[P]): Unit = {
    visitors.foreach(_.visitBody(body, ctx))
    super.visitBody(body, ctx)
  }

  override def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaValue(ctx))
    super.visitMetaValue(ctx)
  }

  override def visitMetaKV(key: String,
                           value: MetaValue,
                           parent: VisitorContext[MetaSection]): Unit = {
    visitors.foreach(_.visitMetaKV(key, value, parent))
    super.visitMetaKV(key, value, parent)
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitMetaSection(ctx))
    super.visitMetaSection(ctx)
  }

  override def visitParameterMetaKV(key: String,
                                    value: MetaValue,
                                    parent: VisitorContext[ParameterMetaSection]): Unit = {
    visitors.foreach(_.visitParameterMetaKV(key, value, parent))
    super.visitParameterMetaKV(key, value, parent)
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

  override def visitRuntimeKV(key: String,
                              value: Expr,
                              parent: VisitorContext[RuntimeSection]): Unit = {
    visitors.foreach(_.visitRuntimeKV(key, value, parent))
    super.visitRuntimeKV(key, value, parent)
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    visitEveryContext(ctx.asInstanceOf[VisitorContext[Element]])
    visitors.foreach(_.visitRuntimeSection(ctx))
    super.visitRuntimeSection(ctx)
  }

  override def visitHintsKV(key: String,
                            value: Expr,
                            parent: VisitorContext[HintsSection]): Unit = {
    visitors.foreach(_.visitHintsKV(key, value, parent))
    super.visitHintsKV(key, value, parent)
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