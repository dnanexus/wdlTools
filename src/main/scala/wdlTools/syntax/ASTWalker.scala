package wdlTools.syntax

import wdlTools.syntax.ASTWalker.Context
import wdlTools.syntax.AbstractSyntax._

class ASTWalker(walkImports: Boolean = false) {
  def visitDocument(ctx: Context[Document]): Unit = {
    visitVersion(createContext[Version, Document](ctx.element.version, ctx))

    ctx.element.elements.collect { case imp: ImportDoc => imp }.foreach { imp =>
      visitImportDoc(createContext[ImportDoc, Document](imp, ctx))
    }

    ctx.element.elements.collect { case struct: TypeStruct => struct }.foreach { imp =>
      visitStruct(createContext[TypeStruct, Document](imp, ctx))
    }

    if (ctx.element.workflow.isDefined) {
      visitWorkflow(createContext[Workflow, Document](ctx.element.workflow.get, ctx))
    }

    ctx.element.elements.collect { case task: Task => task }.foreach { task =>
      visitTask(createContext[Task, Document](task, ctx))
    }
  }

  def visitIdentifier[P <: Element](identifier: String, parent: Context[P]): Unit = {}

  def visitVersion(ctx: Context[Version]): Unit = {}

  def visitImportAlias(ctx: Context[ImportAlias]): Unit = {
    visitIdentifier[ImportAlias](ctx.element.id1, ctx)
    visitIdentifier[ImportAlias](ctx.element.id2, ctx)
  }

  def visitImportDoc(ctx: Context[ImportDoc]): Unit = {
    if (ctx.element.name.isDefined) {
      visitIdentifier[ImportDoc](ctx.element.name.get, ctx)
    }
    ctx.element.aliases.foreach { alias =>
      visitImportAlias(createContext[ImportAlias, ImportDoc](alias, ctx))
    }
    if (walkImports) {
      visitDocument(createContext[Document, ImportDoc](ctx.element.doc, ctx))
    }
  }

  def visitStruct(ctx: Context[TypeStruct]): Unit = {
    ctx.element.members.foreach { member =>
      visitStructMember(createContext[StructMember, TypeStruct](member, ctx))
    }
  }

  def visitDataType(ctx: Context[Type]): Unit = {}

  def visitStructMember(ctx: Context[StructMember]): Unit = {
    visitDataType(createContext[Type, StructMember](ctx.element.dataType, ctx))
    visitIdentifier[StructMember](ctx.element.name, ctx)
  }

  def visitExpression(expr: Context[Expr]): Unit = {}

  def visitDeclaration(ctx: Context[Declaration]): Unit = {
    visitDataType(createContext[Type, Declaration](ctx.element.wdlType, ctx))
    visitIdentifier[Declaration](ctx.element.name, ctx)
    if (ctx.element.expr.isDefined) {
      visitExpression(createContext[Expr, Declaration](ctx.element.expr.get, ctx))
    }
  }

  def visitInputSection(ctx: Context[InputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createContext[Declaration, InputSection](decl, ctx))
    }
  }

  def visitOutputSection(ctx: Context[OutputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createContext[Declaration, OutputSection](decl, ctx))
    }
  }

  def visitCallAlias(ctx: Context[CallAlias]): Unit = {
    visitIdentifier[CallAlias](ctx.element.name, ctx)
  }

  def visitCallInput(ctx: Context[CallInput]): Unit = {
    visitIdentifier[CallInput](ctx.element.name, ctx)
    visitExpression(createContext[Expr, CallInput](ctx.element.expr, ctx))
  }

  def visitCall(ctx: Context[Call]): Unit = {
    visitIdentifier[Call](ctx.element.name, ctx)
    if (ctx.element.alias.isDefined) {
      visitCallAlias(createContext[CallAlias, Call](ctx.element.alias.get, ctx))
    }
    if (ctx.element.inputs.isDefined) {
      ctx.element.inputs.get.value.foreach { inp =>
        visitCallInput(createContext[CallInput, Call](inp, ctx))
      }
    }
  }

  def visitScatter(ctx: Context[Scatter]): Unit = {
    visitIdentifier[Scatter](ctx.element.identifier, ctx)
    visitExpression(createContext[Expr, Scatter](ctx.element.expr, ctx))
    visitBody[Scatter](ctx.element.body, ctx)
  }

  def visitConditional(ctx: Context[Conditional]): Unit = {
    visitExpression(createContext[Expr, Conditional](ctx.element.expr, ctx))
    visitBody[Conditional](ctx.element.body, ctx)
  }

  def visitBody[P <: Element](body: Vector[WorkflowElement], ctx: Context[P]): Unit = {
    body.foreach {
      case decl: Declaration => visitDeclaration(createContext[Declaration, P](decl, ctx))
      case call: Call        => visitCall(createContext[Call, P](call, ctx))
      case scatter: Scatter  => visitScatter(createContext[Scatter, P](scatter, ctx))
      case conditional: Conditional =>
        visitConditional(createContext[Conditional, P](conditional, ctx))
      case other => throw new Exception(s"Unexpected workflow element ${other}")
    }
  }

  def visitMetaKV(ctx: Context[MetaKV]): Unit = {
    visitIdentifier[MetaKV](ctx.element.id, ctx)
    visitExpression(createContext[Expr, MetaKV](ctx.element.expr, ctx))
  }

  def visitMetaSection(ctx: Context[MetaSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitMetaKV(createContext[MetaKV, MetaSection](kv, ctx))
    }
  }

  def visitParameterMetaSection(ctx: Context[ParameterMetaSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitMetaKV(createContext[MetaKV, ParameterMetaSection](kv, ctx))
    }
  }

  def visitWorkflow(ctx: Context[Workflow]): Unit = {
    if (ctx.element.input.isDefined) {
      visitInputSection(createContext[InputSection, Workflow](ctx.element.input.get, ctx))
    }

    visitBody[Workflow](ctx.element.body, ctx)

    if (ctx.element.output.isDefined) {
      visitOutputSection(createContext[OutputSection, Workflow](ctx.element.output.get, ctx))
    }

    if (ctx.element.meta.isDefined) {
      visitMetaSection(createContext[MetaSection, Workflow](ctx.element.meta.get, ctx))
    }

    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          createContext[ParameterMetaSection, Workflow](ctx.element.parameterMeta.get, ctx)
      )
    }
  }

  def visitCommandSection(ctx: Context[CommandSection]): Unit = {
    ctx.element.parts.foreach { expr =>
      visitExpression(createContext[Expr, CommandSection](expr, ctx))
    }
  }

  def visitRuntimeKV(ctx: Context[RuntimeKV]): Unit = {
    visitIdentifier[RuntimeKV](ctx.element.id, ctx)
    visitExpression(createContext[Expr, RuntimeKV](ctx.element.expr, ctx))
  }

  def visitRuntimeSection(ctx: Context[RuntimeSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitRuntimeKV(createContext[RuntimeKV, RuntimeSection](kv, ctx))
    }
  }

  def visitTask(ctx: Context[Task]): Unit = {
    if (ctx.element.input.isDefined) {
      visitInputSection(createContext[InputSection, Task](ctx.element.input.get, ctx))
    }

    visitCommandSection(createContext[CommandSection, Task](ctx.element.command, ctx))

    if (ctx.element.output.isDefined) {
      visitOutputSection(createContext[OutputSection, Task](ctx.element.output.get, ctx))
    }

    if (ctx.element.runtime.isDefined) {
      visitRuntimeSection(createContext[RuntimeSection, Task](ctx.element.runtime.get, ctx))
    }

    if (ctx.element.meta.isDefined) {
      visitMetaSection(createContext[MetaSection, Task](ctx.element.meta.get, ctx))
    }

    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          createContext[ParameterMetaSection, Task](ctx.element.parameterMeta.get, ctx)
      )
    }
  }

  def apply(doc: Document): Unit = {
    val ctx = createContext[Document](doc)
    visitDocument(ctx)
  }

  def createContext[T <: Element](element: T): Context[T] = {
    new Context[T](element)
  }

  def createContext[T <: Element, P <: Element](element: T, parent: Context[P]): Context[T] = {
    new Context[T](element, Some(parent.asInstanceOf[Context[Element]]))
  }
}

object ASTWalker {
  class Context[T <: Element](val element: T, parent: Option[Context[Element]] = None) {
    def getParent[P <: Element]: Context[P] = {
      parent.asInstanceOf[Context[P]]
    }
  }
}
