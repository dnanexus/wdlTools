package wdlTools.types

import scalax.collection.GraphEdge.DiEdge
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import wdlTools.syntax.WdlVersion
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes._

object TypeGraph {

  /**
    * Builds a directed dependency graph from struct types. Built-in types are excluded from
    * the graph. An exception is thrown if there are any missing types. Note that the type
    * alias (the key in the map) may differ from the struct name - the graph uses the aliases.
    * @param structs structs to graph
    * @return a dependency graph
    */
  def buildFromStructTypes(structs: Map[String, T_Struct]): Graph[String, DiEdge] = {
    def extractDependencies(wdlType: T): Vector[String] = {
      wdlType match {
        case T_Optional(t) => extractDependencies(t)
        case T_Array(t, _) => extractDependencies(t)
        case T_Map(k, v)   => extractDependencies(k) ++ extractDependencies(v)
        case T_Pair(l, r)  => extractDependencies(l) ++ extractDependencies(r)
        case T_Struct(name, _) if structs.contains(name) =>
          Vector(name)
        case T_Struct(name, _) =>
          throw new Exception(s"Missing type alias ${name}")
        case _ => Vector.empty
      }
    }

    val graph = structs.foldLeft(Graph.empty[String, DiEdge]) {
      case (graph, (alias, T_Struct(_, members))) =>
        graph ++ members.values.flatMap(extractDependencies).map(dep => dep ~> alias)
    }

    // add in any remaining types, and connect all leafs to the root node
    val missing = structs.keySet.diff(graph.nodes.map(_.value).toSet)
    val leaves = graph.nodes.filterNot(_.hasPredecessors).map(_.value)
    graph ++ (missing ++ leaves).map(GraphUtils.RootNode ~> _)
  }
}

trait ElementInfo {

  /**
    * The WDL Element.
    */
  def element: Element

  /**
    * The element name.
    */
  def name: String

  /**
    * The variables upon which this element depends.
    */
  def imports: Map[String, T]

  /**
    * The variables exported by this element.
    */
  def exports: Map[String, T]

  /**
    * Whether the element is ever referenced. This is implicitly true for all required input variables and all output
    * variables. For other variables, this starts out as false and is updated to true during the graph building process
    * the first time the variable is referenced.
    */
  def referenced: Boolean

  /**
    * Returns a copy of this VarInfo with `referenced` set to `true`.
    */
  private[types] def setReferenced(value: Boolean = true): ElementInfo
}

/**
  * Different kinds of variables:
  * - Input = a variable in the input {} section
  * - Output = a variable in the output {} section
  * - Private = a variable not in input or output
  */
object VariableKind extends Enumeration {
  type VariableKind = Value
  val Input, Output, Private = Value
}

case class VariableInfo(element: Variable,
                        referenced: Boolean,
                        expr: Option[Expr] = None,
                        kind: VariableKind.VariableKind)
    extends ElementInfo {
  override def name: String = element.name

  override lazy val imports: Map[String, T] =
    expr.map(e => TypeUtils.exprDependencies(e)).getOrElse(Map.empty)

  override lazy val exports: Map[String, T] = Map(element.name -> element.wdlType)

  override private[types] def setReferenced(value: Boolean = true): VariableInfo = {
    copy(referenced = value)
  }
}

object VariableInfo {
  def createInputs(inputs: Iterable[InputParameter]): Map[String, VariableInfo] = {
    inputs.map {
      case req: RequiredInputParameter =>
        req.name -> VariableInfo(req, referenced = true, kind = VariableKind.Input)
      case opt: OptionalInputParameter =>
        opt.name -> VariableInfo(opt, referenced = false, kind = VariableKind.Input)
      case optWithDefault: OverridableInputParameterWithDefault =>
        optWithDefault.name -> VariableInfo(optWithDefault,
                                            referenced = false,
                                            expr = Some(optWithDefault.defaultExpr),
                                            kind = VariableKind.Input)
    }.toMap
  }

  def createOutputs(outputs: Iterable[OutputParameter]): Map[String, VariableInfo] = {
    outputs.map { out =>
      out.name -> VariableInfo(out,
                               referenced = true,
                               expr = Some(out.expr),
                               kind = VariableKind.Output)
    }.toMap
  }

  def fromPrivateVariable(privateVariable: PrivateVariable): VariableInfo = {
    VariableInfo(privateVariable,
                 referenced = false,
                 expr = Some(privateVariable.expr),
                 VariableKind.Private)
  }

  def createPrivates(privateVariables: Iterable[PrivateVariable]): Map[String, VariableInfo] = {
    privateVariables.map { decl =>
      decl.name -> fromPrivateVariable(decl)
    }.toMap
  }
}

/**
  * A directed graph of dependencies used within a task or workflow.
  * The `graph` may contain cycles - the first time `dependencyOrder` is called, the graph will be flattened and an
  * exception thrown if the graph is not acyclic.
  * @param graph the dependency graph
  * @param variables a mapping of all the variables in the graph to VarInfo
  */
case class TaskGraph(graph: Graph[String, DiEdge], variables: Map[String, VariableInfo]) {
  lazy val dependencyOrder: Vector[String] = GraphUtils.toOrderedVector(graph)

  def inputOrder: Vector[String] = {
    dependencyOrder.filter(dep => variables(dep).kind == VariableKind.Input)
  }

  def outputOrder: Vector[String] = {
    dependencyOrder.filter(dep => variables(dep).kind == VariableKind.Output)
  }

  def layeredDependencyOrder(cmp: (String, String) => Int): Vector[(Int, Vector[String])] = {
    GraphUtils.toOrderedLayers(graph, cmp)
  }
}

object TaskGraph {
  def build(inputVariables: Vector[InputParameter] = Vector.empty,
            outputVariables: Vector[OutputParameter] = Vector.empty,
            privateVariables: Vector[PrivateVariable] = Vector.empty,
            commandParts: Vector[Expr] = Vector.empty,
            runtime: Map[String, Expr] = Map.empty): TaskGraph = {

    val variables: Map[String, VariableInfo] = {
      VariableInfo.createInputs(inputVariables) ++
        VariableInfo.createOutputs(outputVariables) ++
        VariableInfo.createPrivates(privateVariables)
    }

    def checkDependency(varName: String, depName: String, expr: Option[Expr]): Unit = {
      if (!variables.contains(depName)) {
        val exprStr = expr.map(e => s" expression ${TypeUtils.prettyFormatExpr(e)}").getOrElse("")
        throw new Exception(
            s"${varName}${exprStr} references non-task variable ${depName}"
        )
      } else if (variables(depName).kind == VariableKind.Output && variables(varName).kind != VariableKind.Output) {
        throw new Exception(
            s"Non-output variable ${varName} depends on output variable ${depName}"
        )
      }
    }

    /**
      * Adds all missing dependencies to the graph. Any node with no dependencies is linked to root.
      */
    def addDependencies(names: Iterable[String],
                        graph: Graph[String, DiEdge]): Graph[String, DiEdge] = {
      names.foldLeft(graph) {
        case (g, name) =>
          val decl = variables(name)
          val deps = decl.imports.keySet.map { dep =>
            checkDependency(name, dep, decl.expr)
            (dep, name)
          }
          if (deps.isEmpty) {
            // the node has no dependencies, so link it to root
            g ++ Set(GraphUtils.RootNode ~> name)
          } else {
            // process the dependencies first, then add the edges for this node
            val missing = deps.map(_._1) -- g.nodes.toOuter
            val gNew = if (missing.nonEmpty) {
              addDependencies(missing, g)
            } else {
              g
            }
            gNew ++ deps.map(d => d._1 ~> d._2)
          }
      }
    }

    // Collect required nodes from input, command, runtime, and output blocks
    val commandDeps: Set[String] =
      commandParts.flatMap { expr =>
        TypeUtils.exprDependencies(expr).keySet.map { dep =>
          checkDependency("command", dep, Some(expr))
          dep
        }
      }.toSet
    val runtimeDeps: Set[String] = runtime.values.flatMap { expr =>
      TypeUtils.exprDependencies(expr).keySet.map { dep =>
        checkDependency("runtime", dep, Some(expr))
        dep
      }
    }.toSet
    val requiredNodes = inputVariables.collect {
      case RequiredInputParameter(name, _) => name
    }.toSet | commandDeps | runtimeDeps | outputVariables.map(_.name).toSet
    // create the graph by iteratively adding missing nodes
    val graph = addDependencies(requiredNodes, Graph.empty[String, DiEdge])
    // Update referenced = true for all vars in the graph.
    val updatedVars = variables.map {
      case (name, info) if graph.contains(name) => name -> info.setReferenced()
      case other                                => other
    }
    TaskGraph(graph, updatedVars)
  }

  /**
    * Builds a directed dependency graph of variables used within the scope of a task.
    *
    * The graph is rooted by a special root node (`GraphUtils.RootNode`), which is a "dependency" of all required input
    * variables. The graph is constructed iteratively, with all dependencies pointing to the nodes that depend on them,
    * such that if the graph is sorted topologically starting from the root, the nodes are in the order in which they
    * need to be evaluated.
    *
    * @param task the task to graph
    * @return a tuple (graph, vars), where graph is a `Graph` with nodes being variable names and directed edges, and
    *         vars being a mapping of all variable names to `VarInfo`s.
    * @example
    * task example {
    *   input {
    *     File f
    *     String s = basename(f, ".txt")
    *     String? name
    *     Boolean? b
    *   }
    *   command <<<
    *   ~{default="joe" name}
    *   >>>
    *   output {
    *     String sout = s
    *   }
    * }
    *
    * In this example, `f` is a required input, so the inital graph is `__root__ ~> f`. Next, to successfully evaluate
    * the `command` and `output` sections, we need variables `name` and `s`, so we add nodes
    * `{f ~> s, s ~> sout, __root__ ~> name}`. Since `b` is optional and not referenced anywhere, it is not added to the
    * graph (though it is still included in the returned `vars` map, with its `referenced` attribute set to `false`.
    */
  def buildFrom(task: Task): TaskGraph = {
    build(task.inputs,
          task.outputs,
          task.privateVariables,
          task.command.parts,
          task.runtime.map(_.kvs).getOrElse(Map.empty))
  }
}

case class CallInfo(element: Call, referenced: Boolean = false) extends ElementInfo {
  override def name: String = element.actualName

  override def imports: Map[String, T] = {
    element.inputs.values.flatMap(TypeUtils.exprDependencies).toMap
  }

  override def exports: Map[String, T] = {
    Map(element.actualName -> element.wdlType)
  }

  override private[types] def setReferenced(value: Boolean = true): CallInfo = {
    copy(referenced = value)
  }
}

sealed trait WorkflowBlock {
  def element: BlockElement
  def inputs: Map[String, WdlTypes.T]
  def outputs: Map[String, WdlTypes.T]
}

case class WorkflowScatter(element: Scatter, scatterVars: Set[String]) extends WorkflowBlock {
  val identifier: String = element.identifier
  val expr: TypedAbstractSyntax.Expr = element.expr
  val bodyElements: WorkflowBodyElements =
    WorkflowBodyElements(element.body, scatterVars ++ Set(identifier))

  override def inputs: Map[String, T] = {
    TypeUtils.exprDependencies(expr) ++ bodyElements.inputs
  }

  override def outputs: Map[String, T] = {
    // scatter outputs are always arrays; if expr is non-empty than the
    // output arrays will also be non-empty
    val nonEmpty = expr.wdlType match {
      case WdlTypes.T_Array(_, nonEmpty) => nonEmpty
      case _ =>
        throw new Exception(s"invalid scatter expression type ${expr.wdlType}")
    }
    bodyElements.outputs.map {
      case (name, wdlType) => name -> WdlTypes.T_Array(wdlType, nonEmpty)
    }
  }
}

case class WorkflowConditional(element: Conditional, scatterVars: Set[String])
    extends WorkflowBlock {
  val expr: TypedAbstractSyntax.Expr = element.expr
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(element.body, scatterVars)

  override lazy val inputs: Map[String, T] = {
    TypeUtils.exprDependencies(expr) ++ bodyElements.inputs
  }

  override lazy val outputs: Map[String, T] = {
    // conditional outputs are always optionals
    bodyElements.outputs.map {
      case (name, wdlType) => name -> TypeUtils.ensureOptional(wdlType)
    }
  }
}

case class WorkflowBodyElements(body: Vector[WorkflowElement],
                                scatterVars: Set[String] = Set.empty) {

  def conditionals: Vector[WorkflowConditional] = blocks.collect {
    case cond: WorkflowConditional => cond
  }

  def scatters: Vector[WorkflowScatter] = blocks.collect {
    case scatter: WorkflowScatter => scatter
  }

  lazy val outputs: Map[String, WdlTypes.T] = {
    val privateVariableOutputs = privateVariables.map {
      case (name, v) => name -> v.wdlType
    }
    val callOutputs = calls.flatMap {
      case (callName, c) =>
        Map(callName -> c.wdlType) ++ c.callee.output.map {
          case (fieldName, wdlType) =>
            s"${callName}.${fieldName}" -> wdlType
        }
    }
    val scatterOutputs = scatters.flatMap(_.outputs).toMap
    val conditionalOutputs = conditionals.flatMap(_.outputs).toMap
    privateVariableOutputs ++ callOutputs ++ scatterOutputs ++ conditionalOutputs
  }

  lazy val inputs: Map[String, WdlTypes.T] = {
    val privateVariableInputs =
      privateVariables.values.flatMap(v => TypeUtils.exprDependencies(v.expr))
    val callInputs =
      calls.values.flatMap(c => c.inputs.values.flatMap(TypeUtils.exprDependencies))
    val scatterInputs = scatters.flatMap(_.inputs)
    val conditionalInputs = conditionals.flatMap(_.inputs)
    // any outputs provided by this block (including those provided by
    // nested blocks and by scatter variables of enclosing blocks) are
    // accessible and thus not required as inputs
    (privateVariableInputs ++ callInputs ++ scatterInputs ++ conditionalInputs).filterNot {
      case (name, _) => scatterVars.contains(name) || outputs.contains(name)
    }.toMap
  }
}

abstract class BlockInfo(block: WorkflowBlock, path: Vector[Int]) extends ElementInfo {
  assert(path.nonEmpty, "path must have at least one element")

  override def element: Element = block.element

  override lazy val name: String = s"block_${path.mkString("_")}"
}

case class ConditionalInfo(cond: WorkflowConditional,
                           path: Vector[Int],
                           referenced: Boolean = false)
    extends BlockInfo(cond, path) {
  override def imports: Map[String, T] = {}

  override def exports: Map[String, T] = {}

  override private[types] def setReferenced(value: Boolean = true): ConditionalInfo = {
    copy(referenced = value)
  }
}

case class ScatterInfo(scatter: WorkflowScatter, path: Vector[Int], referenced: Boolean = false)
    extends BlockInfo(scatter, path) {
  override def imports: Map[String, T] = {}

  override def exports: Map[String, T] = {}

  override private[types] def setReferenced(value: Boolean = true): ScatterInfo = {
    copy(referenced = value)
  }
}

case class WorkflowGraph(graph: Graph[String, DiEdge], elements: Map[String, ElementInfo]) {

  /**
    * Splits the workflow into blocks of elements that are optimized for parallel execution, using the following rules.
    * Blocks are returned in dependency order.
    *
    * - calls can be grouped together if
    *   - they are independent OR
    *   - they only depend on each other and/or on closure inputs AND
    *   - `groupCalls` is not `Never` AND
    *     - they are all marked as "shortTask" OR
    *     - `groupCalls` is `Always` OR
    *     - `groupCalls` is `Dependent` and the calls are inter-dependent
    * - conditionals can be grouped with calls and/or with each other, so long as they only contain calls that follow
    *   the same rules as above
    * - each scatter is a block by itself
    * - a conditional that contains a nested scatter is in a block by itself
    * - a variable that is only referenced by one block is added to that block
    * - variables referenced by no other blocks, or by 2 or more other blocks are grouped such that they do not depend
    *   on any of the blocks that depend on them (i.e. there is no circular reference)
    */
  lazy val blocks: Unit = {
    // sort the graph into layered topological order, with each layer ordered by node type
    def cmp(a: String, b: String): Int = {
      (graph.varInfo(a), graph.varInfo(b)) match {
        case _ => ()
      }
    }

    graph.layeredDependencyOrder(cmp)
  }
}

object WorkflowGraph {
//  /**
//   * Checks that a referenced identifier exists. Also checks that, if `depName` refers to an output variable,
//   * `varName` also refers to  an output variable, since output values are not reachable from outside the output
//   * section. If the variable is within a scatter block, checks whether the dependency is the scatter variable first,
//   * and if so, returns the full (uniquified) scatter variable name.
//   */
//  protected def resolveDependency(varName: String,
//                                  depName: String,
//                                  expr: Option[Expr],
//                                  scatterPath: Vector[Int] = Vector.empty): String = {
//    // first try all the possible scatter variable names
//    val scatterVar = scatterPath.inits
//      .flatMap {
//        case path if path.nonEmpty =>
//          Some(s"${GraphUtils.ScatterNodePrefix}${path.mkString("_")}_${depName}")
//        case _ =>
//          None
//      }
//      .collectFirst {
//        case name if allVars.contains(name) => name
//      }
//    if (scatterVar.nonEmpty) {
//      scatterVar.get
//    } else if (allVars.contains(depName)) {
//      if (allVars(depName).kind == DeclarationKind.Output && allVars(varName).kind != DeclarationKind.Output) {
//        throw new Exception(
//          s"Non-output variable ${varName} depends on output variable ${depName}"
//        )
//      }
//      depName
//    } else {
//      val exprStr = expr.map(e => s" expression ${TypeUtils.prettyFormatExpr(e)}").getOrElse("")
//      throw new Exception(
//        s"${varName}${exprStr} references non-task variable ${depName}"
//      )
//    }
//  }
//
//  /**
//   * Adds all missing dependencies to the graph. Any node with no dependencies is linked to root.
//   */
//  protected def addDependencies(
//                                 names: Iterable[String],
//                                 graph: Graph[String, DiEdge],
//                                 scatterPath: Vector[Int] = Vector.empty
//                               ): Graph[String, DiEdge] = {
//    names.foldLeft(graph) {
//      case (g, name) =>
//        allVars(name) match {
//          case DeclInfo(_, _, Some(expr), _) =>
//            val deps = TypeUtils.exprDependencies(expr).keySet.map { dep =>
//              (resolveDependency(name, dep, Some(expr)), name, scatterPath)
//            }
//            if (deps.isEmpty) {
//              // the node has no dependencies, so link it to root
//              g ++ Set(GraphUtils.RootNode ~> name)
//            } else {
//              // process the dependencies first, then add the edges for this node
//              val missing = deps.map(_._1) -- g.nodes.toOuter
//              val gNew = if (missing.nonEmpty) {
//                addDependencies(missing, g)
//              } else {
//                g
//              }
//              gNew ++ deps.map(d => d._1 ~> d._2)
//            }
//          case _ =>
//            // the node has no dependencies, so link it to root
//            g ++ Set(GraphUtils.RootNode ~> name)
//        }
//    }
//  }

  private def getBodyInfos(
      body: Vector[WorkflowElement]
  ): (Map[String, VariableInfo], Map[String, CallInfo], Vector[WorkflowBlock]) = {
    body.foldLeft(
        (Map.empty[String, VariableInfo],
         Map.empty[String, CallInfo],
         Vector.empty[WorkflowBlock],
         0)
    ) {
      case ((declarations, calls, blocks, blockIndex), decl: PrivateVariable)
          if !declarations.contains(decl.name) =>
        (declarations + (decl.name -> VariableInfo.fromPrivateVariable(decl)),
         calls,
         blocks,
         blockIndex)
      case ((declarations, calls, blocks, blockIndex), call: Call)
          if !calls.contains(call.actualName) =>
        (declarations, calls + (call.actualName -> CallInfo(call)), blocks, blockIndex)
      case ((declarations, calls, blocks, blockIndex), scatter: Scatter) =>
        (declarations, calls, blocks :+ WorkflowScatter(scatter, scatterVars), blockIndex + 1)
      case ((declarations, calls, blocks, blockIndex), cond: Conditional) =>
        (declarations, calls, blocks :+ WorkflowConditional(cond, scatterVars), blockIndex + 1)
    }
  }

  def build(inputVariables: Vector[InputParameter] = Vector.empty,
            outputVariables: Vector[OutputParameter] = Vector.empty,
            body: Vector[WorkflowElement],
            blockPath: Vector[Int] = Vector.empty): WorkflowGraph = {

    val elements: Map[String, ElementInfo] = {
      val inputs: Map[String, VariableInfo] = VariableInfo.createInputs(inputVariables)
      val outputs: Map[String, VariableInfo] = VariableInfo.createOutputs(outputVariables)
      val bodyElements = WorkflowBodyElements(body)
      val (privates, calls, blocks) = getBodyInfos(body)

      val blocks = bodyElements.blocks.zipWithIndex.map {
        case (cond: WorkflowConditional, idx) =>
          val info = ConditionalInfo(cond, blockPath :+ idx)
          info.name -> info
        case (scatter: WorkflowScatter, idx) =>
          val info = ScatterInfo(scatter, blockPath :+ idx)
          info.name -> info
      }
      inputs ++ outputs ++ privates ++ calls ++ blocks
    }

    def createBodyInfos(
        bodyElements: WorkflowBodyElements,
        scatterPath: Vector[Int] = Vector.empty
    ): Map[String, ElementInfo] = {
      val decls: Map[String, VariableInfo] = bodyElements.privateVariables.map {
        case (name, decl) =>
          name -> VariableInfo(decl,
                               referenced = false,
                               expr = Some(decl.expr),
                               kind = VariableKind.Private)
      }
      val calls = bodyElements.calls.map {
        case (actualName, call) => actualName -> CallInfo(call)
      }
      val conditionals = bodyElements.conditionals.flatMap { cond =>
        createBodyInfos(cond.bodyElements, scatterPath)
      }
      val scatters = bodyElements.scatters.zipWithIndex.flatMap {
        case (scatter, index) =>
          // we make the scatter variable name unique
          val scatterPrefix = getScatterPrefix(scatterPath :+ index)
          val scatterVar =
            Map(s"${scatterPrefix}${scatter.identifier}" -> ScatterInfo(scatter.element))
          scatterVar ++ createBodyInfos(scatter.bodyElements, scatterPath)
      }
      decls ++ calls ++ conditionals ++ scatters
    }

    def buildBodyGraph(bodyElements: WorkflowBodyElements,
                       graph: Graph[String, DiEdge],
                       scatterPath: Vector[Int] = Vector.empty): Graph[String, DiEdge] = {
      // add top-level dependencies
      val callDeps: Vector[String] = bodyElements.calls.values.flatMap { call =>
        val inputDeps = call.inputs.values.flatMap { expr =>
          TypeUtils.exprDependencies(expr).keySet.map { dep =>
            resolveDependency("command", dep, Some(expr), scatterPath)
          }
        }
        val afterDeps = call.afters.map(_.name)
        inputDeps ++ afterDeps
      }.toVector
      // add dependencies from block (conditional and scatter) expressions
      val blockExprDeps =
        (bodyElements.conditionals.map(_.expr) ++ bodyElements.scatters.map(_.expr)).flatMap {
          expr =>
            TypeUtils.exprDependencies(expr).keySet.map { dep =>
              resolveDependency("command", dep, Some(expr), scatterPath)
            }
        }
      val graphWithTopLevelDeps = addDependencies(callDeps ++ blockExprDeps, graph)
      // add dependencies from nested blocks
      val graphWithConditionals = bodyElements.conditionals.foldLeft(graphWithTopLevelDeps) {
        case (graph, cond) =>
          buildBodyGraph(cond.bodyElements, graph, scatterPath)
      }
      // scatter blocks get access to the (uniqified) scatter variable that is not visible
      val graphWithScatters = bodyElements.scatters.zipWithIndex.foldLeft(graphWithConditionals) {
        case (graph, (scatter, index)) =>
          buildBodyGraph(scatter.bodyElements, graph, scatterPath :+ index)
      }
      graphWithScatters
    }

    def checkDependency(varName: String, depName: String, expr: Option[Expr]): Unit = {
      if (!elements.contains(depName)) {
        val exprStr = expr.map(e => s" expression ${TypeUtils.prettyFormatExpr(e)}").getOrElse("")
        throw new Exception(
            s"${varName}${exprStr} references non-task variable ${depName}"
        )
      } else if (elements(depName).kind == VariableKind.Output && elements(varName).kind != VariableKind.Output) {
        throw new Exception(
            s"Non-output variable ${varName} depends on output variable ${depName}"
        )
      }
    }

    /**
      * Adds all missing dependencies to the graph. Any node with no dependencies is linked to root.
      */
    def addDependencies(names: Iterable[String],
                        graph: Graph[String, DiEdge]): Graph[String, DiEdge] = {
      names.foldLeft(graph) {
        case (g, name) =>
          val decl = elements(name)
          val deps = decl.imports.keySet.map { dep =>
            checkDependency(name, dep, decl.expr)
            (dep, name)
          }
          if (deps.isEmpty) {
            // the node has no dependencies, so link it to root
            g ++ Set(GraphUtils.RootNode ~> name)
          } else {
            // process the dependencies first, then add the edges for this node
            val missing = deps.map(_._1) -- g.nodes.toOuter
            val gNew = if (missing.nonEmpty) {
              addDependencies(missing, g)
            } else {
              g
            }
            gNew ++ deps.map(d => d._1 ~> d._2)
          }
      }
    }

    val initialGraph = addDependencies(inputVariables.collect {
      case RequiredInputParameter(name, _) => name
    }.toSet, Graph.empty[String, DiEdge])

    val bodyElements = WorkflowBodyElements(body)
    val graph = buildBodyGraph(bodyElements, initialGraph)

    // update referenced status of variables
    val updatedVars = elements.map {
      case (name, info) => name -> info.setReferenced(graph.contains(name))
    }

    WorkflowGraph(graph, updatedVars)
  }

  /**
    * Builds a dependency graph from a workflow. This works similarly to building a task dependency graph, with the
    * special exception of scatter variables. According to the WDL scoping rules
    * (https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#value-scopes-and-visibility)
    * a scatter variable is only visible within it's block and sub-blocks.
    */
  def buildFrom(wf: Workflow): WorkflowGraph = {
    build(wf.inputs, wf.outputs, wf.body)
  }
}

object GraphUtils {
  val RootNode: String = "__root__"
  val ScatterNodePrefix: String = "__scatter__"

  /**
    * Gets an ordered Vector of the nodes in `graph` starting at root node `root`. The graph
    * must be directed and acyclic.
    * @param graph a directed graph
    * @param root the root node
    * @tparam X the value type of the graph
    * @return a Vector with all the nodes in the subgraph of `graph` that starts at `root`,
    *         in topological order.
    * @throws Exception if there is a cycle in the graph
    */
  def toOrderedVector[X](graph: Graph[X, DiEdge], root: X, filterNodes: Set[X]): Vector[X] = {
    if (graph.nonEmpty && !graph.contains(root)) {
      throw new Exception(s"Invalid dependency graph - does not contain root node ${root}")
    }
    if (graph.size <= 1) {
      Vector.empty[X]
    } else {
      graph.get(root).withSubgraph().topologicalSort(ignorePredecessors = true) match {
        case Right(value) => value.toVector.map(_.value).filterNot(filterNodes.contains)
        case Left(cycle)  => throw new Exception(s"Graph ${graph} has a cycle at ${cycle}")
      }
    }
  }

  /**
    * Convenience method to get an ordered Vector of the nodes in a String-typed DiGraph.
    * @param graph the graph
    * @param root the root node, defaults to `RootNode`
    * @return
    */
  def toOrderedVector(
      graph: Graph[String, DiEdge],
      root: String = RootNode,
      filterNodes: Set[String] = Set(RootNode)
  ): Vector[String] = {
    toOrderedVector[String](graph, root, filterNodes)
  }

  def toOrderedLayers(
      graph: Graph[String, DiEdge],
      cmp: (String, String) => Int
  ): Vector[(Int, Vector[String])] = {
    def nodeCmp(a: graph.NodeT, b: graph.NodeT): Int = cmp(a.value, b.value)
    val layerOrdering = graph.NodeOrdering(nodeCmp)
    graph.get(GraphUtils.RootNode).withSubgraph().topologicalSort() match {
      case Right(value) =>
        value.withLayerOrdering(layerOrdering).toLayered.toVector.map {
          case (i, nodes) => (i, nodes.toVector.map(_.value))
        }
      case Left(cycle) => throw new Exception(s"Graph ${graph} has a cycle at ${cycle}")
    }
  }
}

case class ElementNode(element: Element)

/**
  * Graph of the DocumentElements in a Document. The graph starts from the main Document and includes all imports.
  *
  * Each Document has a namespace that consists of 1) the workflow, tasks, and structs defined within that document,
  * 2) any imported Documents, which are named either with their alias or (if they don't have an alias) the document
  * name without the path prefix or the '.wdl' suffix, and 3) any structs in imported documents, which are named either
  * by their alias or (if they don't have an alias) by their name. If a Document imports another Document, it has access
  * to all the members of that Document's namespace (using dotted notation).
  */
case class DocumentGraph(graph: Graph[ElementNode, DiEdge],
                         wdlVersion: WdlVersion,
                         namespaces: Map[String, Document],
                         structDefs: Map[String, StructDefinition]) {}

case class DocumentGraphBuilder(root: Document, followImports: Boolean) {
  private val graph = Graph.empty[ElementNode, DiEdge]
  private val wdlVersion: WdlVersion = root.version.value
  private var namespaces: Map[String, Document] = Map.empty
  private var structDefs: Map[String, StructDefinition] = Map.empty
  private var tasks: Map[String, Task] = Map.empty

  private def addDocument(doc: Document, namespace: Option[String] = None): Unit = {
    // coherence check - all documents must have same version
    if (doc.version.value != wdlVersion) {
      throw new TypeException(
          s"""Imported document ${doc.source} has different version than root document: 
             |${wdlVersion} != ${doc.version.value}""".stripMargin,
          doc.loc
      )
    }

    // add all documents except root to the namespace table
    if (namespace.isDefined) {
      namespaces += (namespace.get -> doc)
    }

    val (docImports, docStructDefs, docTasks) = doc.elements.foldLeft(
        (Vector.empty[ImportDoc], Vector.empty[StructDefinition], Vector.empty[Task])
    ) {
      case ((imports, structs, tasks), element: ImportDoc) =>
        (imports :+ element, structs, tasks)
      case ((imports, structs, tasks), element: StructDefinition) =>
        (imports, structs :+ element, tasks)
      case ((imports, structs, tasks), element: Task) =>
        (imports, structs, tasks :+ element)
      case other => throw new RuntimeException(s"Invalid document element ${other}")
    }

    // add all struct defs to the same table since structs use a flat namespace
    docStructDefs.foreach { structDef =>
      if (structDefs.contains(structDef.name)) {
        throw new TypeException(
            s"""All struct definitions in the document graph rooted at ${root} must have unique names; found 
               |duplicate name ${structDef.name}""".stripMargin,
            structDef.loc
        )
      }
      structDefs += (structDef.name -> structDef)
    }
    // add tasks
    docTasks.foreach { task =>
      val fqn = namespace.map(n => s"${n}.${task.name}").getOrElse(task.name)
      if (tasks.contains(fqn)) {
        throw new TypeException(s"""Found task with duplicate name ${fqn}""", task.loc)
      }
      tasks += (fqn -> task)
    }
    // descend workflow and add new elements to the graph
    if (doc.workflow.isDefined) {
      val wf = doc.workflow.get
      //val fqn =
      namespace.map(n => s"${n}.${wf.name}").getOrElse(wf.name)
    }
  }

  def apply(): DocumentGraph = {
    addDocument(root)
    DocumentGraph(graph, wdlVersion, namespaces, structDefs)
  }
}

object DocumentGraph {

  /**
    * Builds an ElementGraph, which wraps a directed graph of the elements in a WDL Document, starting at
    * the top-level WDL and including any imported WDLs.
    * @param root the document to graph
    * @param followImports: whether to include imports in the graph
    * @return
    */
  def build(root: Document, followImports: Boolean): DocumentGraph = {
    DocumentGraphBuilder(root, followImports).apply()
  }
}
