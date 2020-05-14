package wdlTools.types

import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.util.Options
import wdlTools.util.Util.getFilename

import scala.annotation.tailrec
import scala.reflect.ClassTag

class TypedSyntaxTreeVisitor {
  type WdlType = WdlTypes.T

  def visitDocument(ctx: VisitorContext[Document]): Unit = {}

  /**
    * Visit a name in the WDL document's namespace. Does not visit "hidden" names (e.g. a call name
    * is hidden when it has an alias - the alias is the "visible" name in the document's namespace).
    */
  def visitName[P <: Element](name: String, parent: VisitorContext[P]): Unit = {}

  /**
    * Visit a key of a metadata, runtime, or hints section.
    */
  def visitKey[P <: Element](key: String, parent: VisitorContext[P]): Unit = {}

  def visitVersion(ctx: VisitorContext[Version]): Unit = {}

  def visitImportName(name: String, ctx: VisitorContext[ImportDoc]): Unit = {}

  def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {}

  def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {}

  def visitStructMember(name: String,
                        wdlType: WdlType,
                        ctx: VisitorContext[StructDefinition]): Unit = {}

  def visitStruct(ctx: VisitorContext[StructDefinition]): Unit = {}

  def visitExpression(ctx: VisitorContext[Expr]): Unit = {}

  def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {}

  def visitInputSection(ctx: VisitorContext[InputSection]): Unit = {}

  def visitOutputSection(ctx: VisitorContext[OutputSection]): Unit = {}

  def visitCallName(actualName: String,
                    fullyQualifiedName: String,
                    alias: Option[String],
                    ctx: VisitorContext[Call]): Unit = {}

  def visitCall(ctx: VisitorContext[Call]): Unit = {}

  def visitScatter(ctx: VisitorContext[Scatter]): Unit = {}

  def visitConditional(ctx: VisitorContext[Conditional]): Unit = {}

  def visitBody[P <: Element](body: Vector[WorkflowElement], ctx: VisitorContext[P]): Unit = {}

  def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {}

  def visitMetaKV(key: String, value: MetaValue, ctx: VisitorContext[MetaSection]): Unit = {}

  def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitParameterMetaKV(key: String,
                           value: MetaValue,
                           ctx: VisitorContext[ParameterMetaSection]): Unit = {}

  def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {}

  def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {}

  def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {}

  def visitRuntimeKV(key: String, value: Expr, ctx: VisitorContext[RuntimeSection]): Unit = {}

  def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {}

  def visitHintsKV(key: String, value: Expr, ctx: VisitorContext[HintsSection]): Unit = {}

  def visitHintsSection(ctx: VisitorContext[HintsSection]): Unit = {}

  def visitTask(ctx: VisitorContext[Task]): Unit = {}
}

object TypedSyntaxTreeVisitor {
  class VisitorContext[T <: Element](val element: T, val parent: Option[VisitorContext[_]] = None) {

    /**
      * Get the immediate parent of this context, or throw an exception if this context
      * doesn't have a parent
      * @tparam P parent element type
      * @return
      */
    def getParent[P <: Element]: VisitorContext[P] = {
      if (parent.isDefined) {
        parent.get.asInstanceOf[VisitorContext[P]]
      } else {
        throw new Exception("VisitorContext does not have a parent")
      }
    }

    /**
      * Finds the first ancestor of this context that is an executable type
      * (task or workflow).
      */
    def findAncestorExecutable: Option[Element] = {
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

    /**
      * Finds the first ancestor of this context of the specified type.
      * @param tag class tag for P
      * @tparam P ancestor element type to find
      * @return
      */
    def findAncestor[P <: Element](implicit tag: ClassTag[P]): Option[VisitorContext[P]] = {
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

    ctx.element.elements.collect { case struct: StructDefinition => struct }.foreach { imp =>
      visitStruct(createVisitorContext[StructDefinition, Document](imp, ctx))
    }

    if (ctx.element.workflow.isDefined) {
      visitWorkflow(createVisitorContext[Workflow, Document](ctx.element.workflow.get, ctx))
    }

    ctx.element.elements.collect { case task: Task => task }.foreach { task =>
      visitTask(createVisitorContext[Task, Document](task, ctx))
    }
  }

  override def visitImportName(name: String, ctx: VisitorContext[ImportDoc]): Unit = {
    visitName[ImportDoc](name, ctx)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitName[ImportAlias](ctx.element.id2, ctx)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    val name = ctx.element.name
      .getOrElse(
          getFilename(ctx.element.addr).replace(".wdl", "")
      )
    visitImportName(name, ctx)
    ctx.element.aliases.foreach { alias =>
      visitImportAlias(createVisitorContext[ImportAlias, ImportDoc](alias, ctx))
    }
    if (opts.followImports) {
      visitDocument(createVisitorContext[Document, ImportDoc](ctx.element.doc, ctx))
    }
  }

  override def visitStruct(ctx: VisitorContext[StructDefinition]): Unit = {
    visitName[StructDefinition](ctx.element.name, ctx)
    ctx.element.members.foreach {
      case (key, wdlType) => visitStructMember(key, wdlType, ctx)
    }
  }

  /**
    * By default, visitExpression does not traverse compound expressions.
    * This method can be called from an overriding visitExpression to do so.
    */
  def traverseExpression(ctx: VisitorContext[Expr]): Unit = {
    val exprs: Vector[Expr] = ctx.element match {
      case ExprCompoundString(value, _, _)              => value
      case ExprPair(l, r, _, _)                         => Vector(l, r)
      case ExprArray(value, _, _)                       => value
      case ExprMap(value, _, _)                         => value.keys.toVector ++ value.values.toVector
      case ExprObject(value, _, _)                      => value.values.toVector
      case ExprPlaceholderEqual(t, f, value, _, _)      => Vector(t, f, value)
      case ExprPlaceholderDefault(default, value, _, _) => Vector(default, value)
      case ExprPlaceholderSep(sep, value, _, _)         => Vector(sep, value)
      case ExprUniraryPlus(value, _, _)                 => Vector(value)
      case ExprUniraryMinus(value, _, _)                => Vector(value)
      case ExprNegate(value, _, _)                      => Vector(value)
      case ExprLor(a, b, _, _)                          => Vector(a, b)
      case ExprLand(a, b, _, _)                         => Vector(a, b)
      case ExprEqeq(a, b, _, _)                         => Vector(a, b)
      case ExprLt(a, b, _, _)                           => Vector(a, b)
      case ExprGte(a, b, _, _)                          => Vector(a, b)
      case ExprNeq(a, b, _, _)                          => Vector(a, b)
      case ExprLte(a, b, _, _)                          => Vector(a, b)
      case ExprGt(a, b, _, _)                           => Vector(a, b)
      case ExprAdd(a, b, _, _)                          => Vector(a, b)
      case ExprSub(a, b, _, _)                          => Vector(a, b)
      case ExprMod(a, b, _, _)                          => Vector(a, b)
      case ExprMul(a, b, _, _)                          => Vector(a, b)
      case ExprDivide(a, b, _, _)                       => Vector(a, b)
      case ExprAt(array, index, _, _)                   => Vector(array, index)
      case ExprIfThenElse(cond, tBranch, fBranch, _, _) => Vector(cond, tBranch, fBranch)
      case ExprApply(_, _, elements, _, _)              => elements
      case ExprGetName(e, _, _, _)                      => Vector(e)
      case _                                            => Vector.empty
    }
    exprs.foreach { e =>
      traverseExpression(createVisitorContext[Expr, Expr](e, ctx))
    }
  }

  override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
    visitName[Declaration](ctx.element.name, ctx)
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

  override def visitCallName(actualName: String,
                             fullyQualifiedName: String,
                             alias: Option[String],
                             ctx: VisitorContext[Call]): Unit = {
    visitName[Call](alias.getOrElse(actualName), ctx)
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitCallName(ctx.element.actualName, ctx.element.fullyQualifiedName, ctx.element.alias, ctx)
    ctx.element.inputs.foreach { inp =>
      visitExpression(createVisitorContext[Expr, Call](inp._2, ctx))
    }
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitName[Scatter](ctx.element.identifier, ctx)
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

  override def visitMetaKV(key: String,
                           value: MetaValue,
                           ctx: VisitorContext[MetaSection]): Unit = {
    visitKey(key, ctx)
    visitMetaValue(createVisitorContext[MetaValue, MetaSection](value, ctx))
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitMetaKV(key, value, ctx)
    }
  }

  override def visitParameterMetaKV(key: String,
                                    value: MetaValue,
                                    ctx: VisitorContext[ParameterMetaSection]): Unit = {
    visitKey(key, ctx)
    visitMetaValue(createVisitorContext[MetaValue, ParameterMetaSection](value, ctx))
  }

  override def visitParameterMetaSection(ctx: VisitorContext[ParameterMetaSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitParameterMetaKV(key, value, ctx)
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

  override def visitRuntimeKV(key: String,
                              value: Expr,
                              ctx: VisitorContext[RuntimeSection]): Unit = {
    visitName(key, ctx)
    visitExpression(createVisitorContext[Expr, RuntimeSection](value, ctx))
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitRuntimeKV(key, value, ctx)
    }
  }

  override def visitHintsKV(key: String, value: Expr, ctx: VisitorContext[HintsSection]): Unit = {
    visitName(key, ctx)
    visitExpression(createVisitorContext[Expr, HintsSection](value, ctx))
  }

  override def visitHintsSection(ctx: VisitorContext[HintsSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitHintsKV(key, value, ctx)
    }
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    if (ctx.element.input.isDefined) {
      visitInputSection(createVisitorContext[InputSection, Task](ctx.element.input.get, ctx))
    }

    ctx.element.declarations.foreach { decl =>
      visitDeclaration(createVisitorContext[Declaration, Task](decl, ctx))
    }

    visitCommandSection(createVisitorContext[CommandSection, Task](ctx.element.command, ctx))

    if (ctx.element.output.isDefined) {
      visitOutputSection(createVisitorContext[OutputSection, Task](ctx.element.output.get, ctx))
    }

    if (ctx.element.runtime.isDefined) {
      visitRuntimeSection(createVisitorContext[RuntimeSection, Task](ctx.element.runtime.get, ctx))
    }

    if (ctx.element.hints.isDefined) {
      visitHintsSection(createVisitorContext[HintsSection, Task](ctx.element.hints.get, ctx))
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
