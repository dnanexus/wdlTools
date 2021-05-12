package wdlTools.types

import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes._
import dx.util.FileNode

import scala.annotation.tailrec
import scala.reflect.ClassTag

class VisitorContext[E <: Element](val element: E, val parent: Option[VisitorContext[_]] = None) {
  lazy val docSource: FileNode = element match {
    case d: Document => d.source
    case _           => findAncestor[Document].get.element.source
  }

  def createChildContext[C <: Element](element: C): VisitorContext[C] = {
    new VisitorContext[C](element, Some(this.asInstanceOf[VisitorContext[Element]]))
  }

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
  def findAncestorExecutable: Option[Callable] = {
    @tailrec
    def getExecutable(ctx: VisitorContext[_]): Option[Callable] = {
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
    * Finds the first ancestor of this context that is a Document.
    * @return
    */
  def findAncestorDocument: Option[Document] = {
    @tailrec
    def getDocument(ctx: VisitorContext[_]): Option[Document] = {
      ctx.element match {
        case d: Document               => Some(d)
        case _ if ctx.parent.isDefined => getDocument(ctx.parent.get)
        case _                         => None
      }
    }
    getDocument(this)
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

class TypedAbstractSyntaxTreeVisitor {
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

  /**
    * `name` is the actual import name - either the alias or the name of the WDL file
    * without the '.wdl' extension
    */
  def visitImportName(name: String, parent: VisitorContext[ImportDoc]): Unit = {}

  def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {}

  def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {}

  def visitStructMember(name: String,
                        wdlType: WdlType,
                        parent: VisitorContext[StructDefinition]): Unit = {}

  def visitStruct(ctx: VisitorContext[StructDefinition]): Unit = {}

  def visitExpression(ctx: VisitorContext[Expr]): Unit = {}

  /**
    * By default, visitExpression does not traverse compound expressions.
    * This method can be called from an overriding visitExpression to do so.
    */
  def traverseExpression(ctx: VisitorContext[Expr]): Unit = {
    val exprs: Vector[Expr] = ctx.element match {
      case ExprCompoundString(value, _) => value
      case ExprPair(l, r, _)            => Vector(l, r)
      case ExprArray(value, _)          => value
      case ExprMap(value, _)            => value.keys.toVector ++ value.values.toVector
      case ExprObject(value, _)         => value.keys.toVector ++ value.values.toVector
      case ExprPlaceholder(t, f, sep, default, value, _) =>
        Vector(t, f, sep, default).flatten :+ value
      case ExprAt(array, index, _)                   => Vector(array, index)
      case ExprIfThenElse(cond, tBranch, fBranch, _) => Vector(cond, tBranch, fBranch)
      case ExprApply(_, _, elements, _)              => elements
      case ExprGetName(e, _, _)                      => Vector(e)
      case _                                         => Vector.empty
    }
    exprs.foreach { e =>
      traverseExpression(ctx.createChildContext[Expr](e))
    }
  }

  def visitDeclaration(ctx: VisitorContext[PrivateVariable]): Unit = {}

  def visitInputDefinition(ctx: VisitorContext[InputParameter]): Unit = {}

  def visitOutputDefinition(ctx: VisitorContext[OutputParameter]): Unit = {}

  def visitCallName(actualName: String,
                    fullyQualifiedName: String,
                    alias: Option[String],
                    parent: VisitorContext[Call]): Unit = {}

  def visitCall(ctx: VisitorContext[Call]): Unit = {}

  def visitScatter(ctx: VisitorContext[Scatter]): Unit = {}

  def visitConditional(ctx: VisitorContext[Conditional]): Unit = {}

  def visitBody[P <: Element](body: Vector[WorkflowElement], parent: VisitorContext[P]): Unit = {}

  def visitMetaValue(ctx: VisitorContext[MetaValue]): Unit = {}

  def visitMetaKV(key: String, value: MetaValue, parent: VisitorContext[MetaSection]): Unit = {}

  def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitParameterMetaKV(key: String,
                           value: MetaValue,
                           parent: VisitorContext[MetaSection]): Unit = {}

  def visitParameterMetaSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {}

  def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {}

  def visitRuntimeKV(key: String, value: Expr, parent: VisitorContext[RuntimeSection]): Unit = {}

  def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {}

  def visitHintsKV(key: String, value: MetaValue, parent: VisitorContext[MetaSection]): Unit = {}

  def visitHintsSection(ctx: VisitorContext[MetaSection]): Unit = {}

  def visitTask(ctx: VisitorContext[Task]): Unit = {}
}

class TypedAbstractSyntaxTreeWalker(followImports: Boolean = false)
    extends TypedAbstractSyntaxTreeVisitor {
  override def visitDocument(ctx: VisitorContext[Document]): Unit = {
    visitVersion(ctx.createChildContext[Version](ctx.element.version))

    ctx.element.elements.collect { case imp: ImportDoc => imp }.foreach { imp =>
      visitImportDoc(ctx.createChildContext[ImportDoc](imp))
    }

    ctx.element.elements.collect { case struct: StructDefinition => struct }.foreach { imp =>
      visitStruct(ctx.createChildContext[StructDefinition](imp))
    }

    if (ctx.element.workflow.isDefined) {
      visitWorkflow(ctx.createChildContext[Workflow](ctx.element.workflow.get))
    }

    ctx.element.elements.collect { case task: Task => task }.foreach { task =>
      visitTask(ctx.createChildContext[Task](task))
    }
  }

  override def visitImportName(name: String, parent: VisitorContext[ImportDoc]): Unit = {
    visitName[ImportDoc](name, parent)
  }

  override def visitImportAlias(ctx: VisitorContext[ImportAlias]): Unit = {
    visitName[ImportAlias](ctx.element.id2, ctx)
  }

  override def visitImportDoc(ctx: VisitorContext[ImportDoc]): Unit = {
    visitImportName(ctx.element.namespace, ctx)
    ctx.element.aliases.foreach { alias =>
      visitImportAlias(ctx.createChildContext[ImportAlias](alias))
    }
    if (followImports) {
      visitDocument(ctx.createChildContext[Document](ctx.element.doc))
    }
  }

  override def visitStruct(ctx: VisitorContext[StructDefinition]): Unit = {
    visitName[StructDefinition](ctx.element.name, ctx)
    ctx.element.members.foreach {
      case (key, wdlType) => visitStructMember(key, wdlType, ctx)
    }
  }

  def visitVariable[P <: Element](name: String,
                                  wdlType: T,
                                  expr: Option[Expr],
                                  parent: VisitorContext[P]): Unit = {
    visitName(name, parent)
    if (expr.isDefined) {
      visitExpression(parent.createChildContext[Expr](expr.get))
    }
  }

  override def visitDeclaration(ctx: VisitorContext[PrivateVariable]): Unit = {
    visitVariable(ctx.element.name, ctx.element.wdlType, Some(ctx.element.expr), ctx)
  }

  override def visitInputDefinition(ctx: VisitorContext[InputParameter]): Unit = {
    ctx.element match {
      case RequiredInputParameter(name, wdlType) => visitVariable(name, wdlType, None, ctx)
      case OptionalInputParameter(name, wdlType) => visitVariable(name, wdlType, None, ctx)
      case OverridableInputParameterWithDefault(name, wdlType, defaultExpr) =>
        visitVariable(name, wdlType, Some(defaultExpr), ctx)
    }
  }

  override def visitOutputDefinition(ctx: VisitorContext[OutputParameter]): Unit = {
    visitVariable(ctx.element.name, ctx.element.wdlType, Some(ctx.element.expr), ctx)
  }

  override def visitCallName(actualName: String,
                             fullyQualifiedName: String,
                             alias: Option[String],
                             parent: VisitorContext[Call]): Unit = {
    visitName[Call](alias.getOrElse(actualName), parent)
  }

  override def visitCall(ctx: VisitorContext[Call]): Unit = {
    visitCallName(ctx.element.actualName, ctx.element.fullyQualifiedName, ctx.element.alias, ctx)
    ctx.element.inputs.foreach { inp =>
      visitExpression(ctx.createChildContext[Expr](inp._2))
    }
  }

  override def visitScatter(ctx: VisitorContext[Scatter]): Unit = {
    visitName[Scatter](ctx.element.identifier, ctx)
    visitExpression(ctx.createChildContext[Expr](ctx.element.expr))
    visitBody[Scatter](ctx.element.body, ctx)
  }

  override def visitConditional(ctx: VisitorContext[Conditional]): Unit = {
    visitExpression(ctx.createChildContext[Expr](ctx.element.expr))
    visitBody[Conditional](ctx.element.body, ctx)
  }

  override def visitBody[P <: Element](body: Vector[WorkflowElement],
                                       parent: VisitorContext[P]): Unit = {
    body.foreach {
      case decl: PrivateVariable =>
        visitDeclaration(parent.createChildContext[PrivateVariable](decl))
      case call: Call       => visitCall(parent.createChildContext[Call](call))
      case scatter: Scatter => visitScatter(parent.createChildContext[Scatter](scatter))
      case conditional: Conditional =>
        visitConditional(parent.createChildContext[Conditional](conditional))
      case other => throw new Exception(s"Unexpected workflow element ${other}")
    }
  }

  override def visitMetaKV(key: String,
                           value: MetaValue,
                           parent: VisitorContext[MetaSection]): Unit = {
    visitKey(key, parent)
    visitMetaValue(parent.createChildContext[MetaValue](value))
  }

  override def visitMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitMetaKV(key, value, ctx)
    }
  }

  override def visitParameterMetaKV(key: String,
                                    value: MetaValue,
                                    parent: VisitorContext[MetaSection]): Unit = {
    visitKey(key, parent)
    visitMetaValue(parent.createChildContext[MetaValue](value))
  }

  override def visitParameterMetaSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitParameterMetaKV(key, value, ctx)
    }
  }

  override def visitWorkflow(ctx: VisitorContext[Workflow]): Unit = {
    visitName[Workflow](ctx.element.name, ctx)
    ctx.element.inputs.foreach { inp =>
      visitInputDefinition(ctx.createChildContext[InputParameter](inp))
    }
    visitBody[Workflow](ctx.element.body, ctx)
    ctx.element.outputs.foreach { out =>
      visitOutputDefinition(ctx.createChildContext[OutputParameter](out))
    }
    if (ctx.element.meta.isDefined) {
      visitMetaSection(ctx.createChildContext[MetaSection](ctx.element.meta.get))
    }
    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          ctx.createChildContext[MetaSection](ctx.element.parameterMeta.get)
      )
    }
  }

  override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
    ctx.element.parts.foreach { expr =>
      visitExpression(ctx.createChildContext[Expr](expr))
    }
  }

  override def visitRuntimeKV(key: String,
                              value: Expr,
                              parent: VisitorContext[RuntimeSection]): Unit = {
    visitName(key, parent)
    visitExpression(parent.createChildContext[Expr](value))
  }

  override def visitRuntimeSection(ctx: VisitorContext[RuntimeSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitRuntimeKV(key, value, ctx)
    }
  }

  override def visitHintsKV(key: String,
                            value: MetaValue,
                            parent: VisitorContext[MetaSection]): Unit = {
    visitName(key, parent)
    visitMetaValue(parent.createChildContext[MetaValue](value))
  }

  override def visitHintsSection(ctx: VisitorContext[MetaSection]): Unit = {
    ctx.element.kvs.foreach {
      case (key, value) => visitHintsKV(key, value, ctx)
    }
  }

  override def visitTask(ctx: VisitorContext[Task]): Unit = {
    visitName[Task](ctx.element.name, ctx)
    ctx.element.inputs.foreach { inp =>
      visitInputDefinition(ctx.createChildContext[InputParameter](inp))
    }
    ctx.element.privateVariables.foreach { decl =>
      visitDeclaration(ctx.createChildContext[PrivateVariable](decl))
    }
    visitCommandSection(ctx.createChildContext[CommandSection](ctx.element.command))
    ctx.element.outputs.foreach { out =>
      visitOutputDefinition(ctx.createChildContext[OutputParameter](out))
    }
    if (ctx.element.runtime.isDefined) {
      visitRuntimeSection(ctx.createChildContext[RuntimeSection](ctx.element.runtime.get))
    }
    if (ctx.element.hints.isDefined) {
      visitHintsSection(ctx.createChildContext[MetaSection](ctx.element.hints.get))
    }
    if (ctx.element.meta.isDefined) {
      visitMetaSection(ctx.createChildContext[MetaSection](ctx.element.meta.get))
    }
    if (ctx.element.parameterMeta.isDefined) {
      visitParameterMetaSection(
          ctx.createChildContext[MetaSection](ctx.element.parameterMeta.get)
      )
    }
  }

  def apply(doc: Document): Unit = {
    visitDocument(createRootContext(doc))
  }

  protected def createRootContext(doc: Document): VisitorContext[Document] = {
    new VisitorContext[Document](doc)
  }
}
