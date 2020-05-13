package wdlTools.syntax

import wdlTools.syntax.AbstractSyntaxTreeVisitor.VisitorContext
import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.Options

import scala.annotation.tailrec
import scala.reflect.ClassTag

class AbstractSyntaxTreeVisitor {
  def visitDocument(ctx: VisitorContext[Document]): Unit = {}

  def visitIdentifier[P <: Element](identifier: String, parent: VisitorContext[P]): Unit = {}

  def visitVersion(ctx: VisitorContext[Version]): Unit = {}

  def visitImportName(ctx: VisitorContext[ImportName]): Unit = {}

  def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {}

  def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {}

  def visitStruct(ctx: VisitorContext[TypeStruct]): Unit = {}

  def visitDataType(ctx: VisitorContext[Type]): Unit = {}

  def visitStructMember(ctx: VisitorContext[StructMember]): Unit = {}

  def visitExpression(ctx: VisitorContext[Expr]): Unit = {}

  def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {}

  def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {}

  def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {}

  def visitCallAlias(ctx: VisitorContext[CallAlias]): Unit = {}

  def visitCallInput(ctx: VisitorContext[CallInput]): Unit = {}

  def visitCall(ctx: VisitorContext[Call]): Unit = {}

  def visitScatter(ctx: VisitorContext[Scatter]): Unit = {}

  def visitConditional(ctx: VisitorContext[Conditional]): Unit = {}

  def visitBody[P <: Element](body: Vector[WorkflowElement], ctx: VisitorContext[P]): Unit = {}

  def visitMetaKV(ctx: VisitorContext[MetaKV]): Unit = {}

  def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {}

  def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {}

  def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {}

  def visitRuntimeKV(ctx: VisitorContext[RuntimeKV]): Unit = {}

  def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {}

  def visitTask(ctx: VisitorContext[Task]): Unit = {}
}

object AbstractSyntaxTreeVisitor {
  class VisitorContext[T <: Element](val element: T, val parent: Option[VisitorContext[_]] = None) {
    def getParent[P <: Element]: VisitorContext[P] = {
      if (parent.isDefined) {
        parent.get.asInstanceOf[VisitorContext[P]]
      } else {
        throw new Exception("Context does not have a parent")
      }
    }

    def getParentExecutable: Option[Element] = {
      @tailrec
      def getExecutable(ctx: VisitorContext[_]): Option[Element] = {
        ctx.element match {
          case t: Task                   => Some(t)
          case w: Workflow               => Some(w)
          case _ if ctx.parent.isDefined => getExecutable(ctx.parent.get)
          case _                         => None
        }
      }
      getExecutable(this)
    }

    def findParent[P <: Element](implicit tag: ClassTag[P]): Option[VisitorContext[P]] = {
      if (parent.isDefined) {
        @tailrec
        def find(ctx: VisitorContext[_]): Option[VisitorContext[P]] = {
          ctx.element match {
            case _: P                      => Some(ctx.asInstanceOf[VisitorContext[P]])
            case _ if ctx.parent.isDefined => find(ctx.parent.get)
            case _                         => None
          }
        }
        find(this.parent.get)
      } else {
        None
      }
    }
  }
}

class AbstractSyntaxTreeWalker(opts: Options) extends AbstractSyntaxTreeVisitor {
  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
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

  override def visitImportName(ctx: VisitorContext[ImportName]): Unit = {
    visitIdentifier[ImportName](ctx.element.value, ctx)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitIdentifier[ImportAlias](ctx.element.id1, ctx)
    visitIdentifier[ImportAlias](ctx.element.id2, ctx)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    if (ctx.element.name.isDefined) {
      visitImportName(createContext[ImportName, ImportDoc](ctx.element.name.get, ctx))
    }
    ctx.element.aliases.foreach { alias =>
      visitImportAlias(createContext[ImportAlias, ImportDoc](alias, ctx))
    }
    if (opts.followImports) {
      visitDocument(createContext[Document, ImportDoc](ctx.element.doc.get, ctx))
    }
  }

  override def visitStruct(ctx: VisitorContext[TypeStruct]): Unit = {
    ctx.element.members.foreach { member =>
      visitStructMember(createContext[StructMember, TypeStruct](member, ctx))
    }
  }

  override def visitStructMember(ctx: VisitorContext[StructMember]): Unit = {
    visitDataType(createContext[Type, StructMember](ctx.element.dataType, ctx))
    visitIdentifier[StructMember](ctx.element.name, ctx)
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitDataType(createContext[Type, Declaration](ctx.element.wdlType, ctx))
    visitIdentifier[Declaration](ctx.element.name, ctx)
    if (ctx.element.expr.isDefined) {
      visitExpression(createContext[Expr, Declaration](ctx.element.expr.get, ctx))
    }
  }

  override def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createContext[Declaration, InputSection](decl, ctx))
    }
  }

  override def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createContext[Declaration, OutputSection](decl, ctx))
    }
  }

  override def visitCallAlias(ctx: VisitorContext[CallAlias]): Unit = {
    visitIdentifier[CallAlias](ctx.element.name, ctx)
  }

  override def visitCallInput(ctx: VisitorContext[CallInput]): Unit = {
    visitIdentifier[CallInput](ctx.element.name, ctx)
    visitExpression(createContext[Expr, CallInput](ctx.element.expr, ctx))
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
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

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitIdentifier[Scatter](ctx.element.identifier, ctx)
    visitExpression(createContext[Expr, Scatter](ctx.element.expr, ctx))
    visitBody[Scatter](ctx.element.body, ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitExpression(createContext[Expr, Conditional](ctx.element.expr, ctx))
    visitBody[Conditional](ctx.element.body, ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       ctx: VisitorContext[P]): Unit = {
    body.foreach {
      case decl: Declaration => visitDeclaration(createContext[Declaration, P](decl, ctx))
      case call: Call        => visitCall(createContext[Call, P](call, ctx))
      case scatter: Scatter  => visitScatter(createContext[Scatter, P](scatter, ctx))
      case conditional: Conditional =>
        visitConditional(createContext[Conditional, P](conditional, ctx))
      case other => throw new Exception(s"Unexpected workflow element ${other}")
    }
  }

  override def visitMetaKV(ctx: VisitorContext[MetaKV]): Unit = {
    visitIdentifier[MetaKV](ctx.element.id, ctx)
    visitExpression(createContext[Expr, MetaKV](ctx.element.expr, ctx))
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitMetaKV(createContext[MetaKV, MetaSection](kv, ctx))
    }
  }

  override def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitMetaKV(createContext[MetaKV, ParameterMetaSection](kv, ctx))
    }
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
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

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    ctx.element.parts.foreach { expr =>
      visitExpression(createContext[Expr, CommandSection](expr, ctx))
    }
  }

  override def visitRuntimeKV(ctx: VisitorContext[RuntimeKV]): Unit = {
    visitIdentifier[RuntimeKV](ctx.element.id, ctx)
    visitExpression(createContext[Expr, RuntimeKV](ctx.element.expr, ctx))
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitRuntimeKV(createContext[RuntimeKV, RuntimeSection](kv, ctx))
    }
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
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

  def createContext[T <: Element](element: T): VisitorContext[T] = {
    new VisitorContext[T](element)
  }

  def createContext[T <: Element, P <: Element](element: T,
                                                parent: VisitorContext[P]): VisitorContext[T] = {
    new VisitorContext[T](element, Some(parent.asInstanceOf[VisitorContext[Element]]))
  }
}
