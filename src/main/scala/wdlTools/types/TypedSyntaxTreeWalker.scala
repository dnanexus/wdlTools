package wdlTools.types

import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.util.Options

import scala.annotation.tailrec
import scala.reflect.ClassTag

class TypedSyntaxTreeVisitor {
  def visitDocument(ctx: VisitorContext[Document]): Unit = {}

  def visitIdentifier[P <: Element](identifier: String, parent: VisitorContext[P]): Unit = {}

  def visitVersion(ctx: VisitorContext[Version]): Unit = {}

  def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {}

  def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {}

  def visitExpression(ctx: VisitorContext[Expr]): Unit = {}

  def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {}

  def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {}

  def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {}

  def visitCall(ctx: VisitorContext[Call]): Unit = {}

  def visitScatter(ctx: VisitorContext[Scatter]): Unit = {}

  def visitConditional(ctx: VisitorContext[Conditional]): Unit = {}

  def visitBody[P <: Element](body: Vector[WorkflowElement], ctx: VisitorContext[P]): Unit = {}

  def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {}

  def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {}

  def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {}

  def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {}

  def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {}

  def visitTask(ctx: VisitorContext[Task]): Unit = {}
}

object TypedSyntaxTreeVisitor {
  class VisitorContext[T <: Element](val element: T, val parent: Option[VisitorContext[_]] = None) {
    def getParent[P <: Element]: VisitorContext[P] = {
      if (parent.isDefined) {
        parent.get.asInstanceOf[VisitorContext[P]]
      } else {
        throw new Exception("VisitorContext does not have a parent")
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

class TypedSyntaxTreeWalker(opts: Options) extends TypedSyntaxTreeVisitor {
  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
    visitVersion(createVisitorContext[Version, Document](ctx.element.version, ctx))

    ctx.element.elements.collect { case imp: ImportDoc => imp }.foreach { imp =>
      visitImportDoc(createVisitorContext[ImportDoc, Document](imp, ctx))
    }

    if (ctx.element.workflow.isDefined) {
      visitWorkflow(createVisitorContext[Workflow, Document](ctx.element.workflow.get, ctx))
    }

    ctx.element.elements.collect { case task: Task => task }.foreach { task =>
      visitTask(createVisitorContext[Task, Document](task, ctx))
    }
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitIdentifier[ImportAlias](ctx.element.id1, ctx)
    visitIdentifier[ImportAlias](ctx.element.id2, ctx)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    ctx.element.aliases.foreach { alias =>
      visitImportAlias(createVisitorContext[ImportAlias, ImportDoc](alias, ctx))
    }
    if (opts.followImports) {
      visitDocument(createVisitorContext[Document, ImportDoc](ctx.element.doc, ctx))
    }
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitIdentifier[Declaration](ctx.element.name, ctx)
    if (ctx.element.expr.isDefined) {
      visitExpression(createVisitorContext[Expr, Declaration](ctx.element.expr.get, ctx))
    }
  }

  override def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createVisitorContext[Declaration, InputSection](decl, ctx))
    }
  }

  override def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {
    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createVisitorContext[Declaration, OutputSection](decl, ctx))
    }
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitIdentifier[Call](ctx.element.actualName, ctx)
    ctx.element.inputs.foreach { inp =>
      visitIdentifier(inp._1, ctx)
      visitExpression(createVisitorContext[Expr, Call](inp._2, ctx))
    }
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitIdentifier[Scatter](ctx.element.identifier, ctx)
    visitExpression(createVisitorContext[Expr, Scatter](ctx.element.expr, ctx))
    visitBody[Scatter](ctx.element.body, ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitExpression(createVisitorContext[Expr, Conditional](ctx.element.expr, ctx))
    visitBody[Conditional](ctx.element.body, ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       ctx: VisitorContext[P]): Unit = {
    body.foreach {
      case decl: Declaration => visitDeclaration(createVisitorContext[Declaration, P](decl, ctx))
      case call: Call        => visitCall(createVisitorContext[Call, P](call, ctx))
      case scatter: Scatter  => visitScatter(createVisitorContext[Scatter, P](scatter, ctx))
      case conditional: Conditional =>
        visitConditional(createVisitorContext[Conditional, P](conditional, ctx))
      case other => throw new Exception(s"Unexpected workflow element ${other}")
    }
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitIdentifier(kv._1, ctx)
      visitMetaValue(createVisitorContext[MetaValue, MetaSection](kv._2, ctx))
    }
  }

  override def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {
    ctx.element.kvs.values.foreach { kv =>
      visitMetaValue(createVisitorContext[MetaValue, ParameterMetaSection](kv, ctx))
    }
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
    if (ctx.element.input.isDefined) {
      visitInputSection(createVisitorContext[InputSection, Workflow](ctx.element.input.get, ctx))
    }

    visitBody[Workflow](ctx.element.body, ctx)

    if (ctx.element.output.isDefined) {
      visitOutputSection(createVisitorContext[OutputSection, Workflow](ctx.element.output.get, ctx))
    }

    if (ctx.element.meta.isDefined) {
      visitMetaSection(createVisitorContext[MetaSection, Workflow](ctx.element.meta.get, ctx))
    }

    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          createVisitorContext[ParameterMetaSection, Workflow](ctx.element.parameterMeta.get, ctx)
      )
    }
  }

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    ctx.element.parts.foreach { expr =>
      visitExpression(createVisitorContext[Expr, CommandSection](expr, ctx))
    }
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    ctx.element.kvs.foreach { kv =>
      visitIdentifier(kv._1, ctx)
      visitExpression(createVisitorContext[Expr, RuntimeSection](kv._2, ctx))
    }
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    if (ctx.element.input.isDefined) {
      visitInputSection(createVisitorContext[InputSection, Task](ctx.element.input.get, ctx))
    }

    visitCommandSection(createVisitorContext[CommandSection, Task](ctx.element.command, ctx))

    if (ctx.element.output.isDefined) {
      visitOutputSection(createVisitorContext[OutputSection, Task](ctx.element.output.get, ctx))
    }

    if (ctx.element.runtime.isDefined) {
      visitRuntimeSection(createVisitorContext[RuntimeSection, Task](ctx.element.runtime.get, ctx))
    }

    if (ctx.element.meta.isDefined) {
      visitMetaSection(createVisitorContext[MetaSection, Task](ctx.element.meta.get, ctx))
    }

    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          createVisitorContext[ParameterMetaSection, Task](ctx.element.parameterMeta.get, ctx)
      )
    }
  }

  def apply(doc: Document): Unit = {
    val ctx = createVisitorContext[Document](doc)
    visitDocument(ctx)
  }

  def createVisitorContext[T <: Element](element: T): VisitorContext[T] = {
    new VisitorContext[T](element)
  }

  def createVisitorContext[T <: Element, P <: Element](
      element: T,
      parent: VisitorContext[P]
  ): VisitorContext[T] = {
    new VisitorContext[T](element, Some(parent.asInstanceOf[VisitorContext[Element]]))
  }
}
