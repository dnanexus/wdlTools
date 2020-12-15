package wdlTools.types

import scalax.collection.GraphEdge.DiEdge
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import wdlTools.syntax.WdlVersion
import wdlTools.types.ExprGraph.VarInfo
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes._

case class ElementNode(element: Element)

/**
  * Graph of the DocumentElements in a Document. The graph starts from
  * the main Document and includes all imports.
  *
  * Each Document has a namespace that consists of 1) the workflow,
  * tasks, and structs defined within that document, 2) any imported
  * Documents, which are named either with their alias or (if they
  * don't have an alias) the document name without the path prefix or
  * the '.wdl' suffix, and 3) any structs in imported documents, which
  * are named either by their alias or (if they don't have an alias) by
  * their name. If a Document imports another Document, it has access to
  * all the members of that Document's namespace (using dotted notation).
  *
  * @param graph
  * @param wdlVersion
  * @param namespaces
  * @param structDefs
  */
case class ElementGraph(graph: Graph[ElementNode, DiEdge],
                        wdlVersion: WdlVersion,
                        namespaces: Map[String, Document],
                        structDefs: Map[String, StructDefinition]) {}

case class ElementGraphBuilder(root: Document, followImports: Boolean) {
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

    val docElements = DocumentElements(doc)
    // add all documents except root to the namespace table
    if (namespace.isDefined) {
      namespaces += (namespace.get -> doc)
    }
    // add all struct defs to the same table since structs use a flat namespace
    docElements.structDefs.foreach { structDef =>
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
    docElements.tasks.foreach { task =>
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

  def apply(): ElementGraph = {
    addDocument(root)
    ElementGraph(graph, wdlVersion, namespaces, structDefs)
  }
}

object ElementGraph {

  /**
    * Builds an ElementGraph, which wraps a directed graph of the elements in a WDL Document, starting at
    * the top-level WDL and including any imported WDLs.
    * @param root the document to graph
    * @param followImports: whether to include imports in the graph
    * @return
    */
  def build(root: Document, followImports: Boolean): ElementGraph = {
    ElementGraphBuilder(root, followImports).apply()
  }
}

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

/**
  * A directed graph of dependencies used within a task or workflow.
  * Note that `graph` may contain cycles - the first time `dependencyOrder`
  * is called, the graph will be flattened and an exception thrown if the
  * graph is not acyclic.
  * @param graph the dependency graph
  * @param varInfo a mapping of all the variables in the graph to VarInfo
  */
case class ExprGraph(graph: Graph[String, DiEdge], varInfo: Map[String, VarInfo]) {
  lazy val dependencyOrder: Vector[String] = GraphUtils.toOrderedVector(graph)

  def inputOrder: Vector[String] = {
    dependencyOrder.collect {
      case dep if varInfo(dep).kind == ExprGraph.VarKind.Input => dep
    }
  }

  def outputOrder: Vector[String] = {
    dependencyOrder.collect {
      case dep if varInfo(dep).kind == ExprGraph.VarKind.Output => dep
    }
  }
}

object ExprGraph {

  /**
    * Different kinds of variables:
    * - Input = a variable in the input {} section
    * - Output = a variable in the output {} section
    * - Intermediate = a variable not in input or output
    * - PostCommand = an Intermediate task variable that
    *   depends on the command block being evaluated
    */
  object VarKind extends Enumeration {
    val Input, Output, Private, Scatter = Value
  }

  trait VarInfo {

    /**
      * The WDL Element.
      */
    def element: Element

    /**
      * Whether the element is ever referenced. This is implicitly
      * true for all input and output variables. For intermediate
      * variables, this starts out as false and is updated to true
      * during the graph building process the first time the variable
      * is referenced.
      */
    def referenced: Boolean

    /**
      * The kind of variable - indicates where the variable is defined.
      * @return
      */
    def kind: VarKind.Value

    /**
      * Returns a copy of this VarInfo with `referenced` set to `true`.
      * @return
      */
    def setReferenced(value: Boolean = true): VarInfo
  }

  case class DeclInfo(element: Variable,
                      referenced: Boolean,
                      expr: Option[Expr] = None,
                      kind: VarKind.Value)
      extends VarInfo {
    override def setReferenced(value: Boolean = true): VarInfo = copy(referenced = value)
  }

  abstract class ExprGraphBuilder(inputDefs: Vector[InputParameter],
                                  outputDefs: Vector[OutputParameter]) {
    protected lazy val inputs: Map[String, VarInfo] = {
      inputDefs.map {
        case req: RequiredInputParameter =>
          req.name -> DeclInfo(req, referenced = true, kind = VarKind.Input)
        case opt: OptionalInputParameter =>
          opt.name -> DeclInfo(opt, referenced = false, kind = VarKind.Input)
        case optWithDefault: OverridableInputParameterWithDefault =>
          optWithDefault.name -> DeclInfo(optWithDefault,
                                          referenced = false,
                                          expr = Some(optWithDefault.defaultExpr),
                                          kind = VarKind.Input)
      }.toMap
    }

    protected lazy val outputs: Map[String, VarInfo] = {
      outputDefs.map { out =>
        out.name -> DeclInfo(out, referenced = true, expr = Some(out.expr), kind = VarKind.Output)
      }.toMap
    }

    protected def allVars: Map[String, VarInfo]

    /**
      * Checks that a referenced identifier exists. Also checks that, if
      * `depName` refers to an output variable, `varName` also refers to
      * an output variable, since output values are not reachable from
      * outside the output section. If the variable is within a scatter
      * block, checks whether the dependency is the scatter variable first,
      * and if so, returns the full (uniquified) scatter variable name.
      */
    protected def resolveDependency(varName: String,
                                    depName: String,
                                    expr: Option[Expr],
                                    scatterPath: Vector[Int] = Vector.empty): String = {
      // first try all the possible scatter variable names
      val scatterVar = scatterPath.inits
        .flatMap {
          case path if path.nonEmpty =>
            Some(s"${GraphUtils.ScatterNodePrefix}${path.mkString("_")}_${depName}")
          case _ =>
            None
        }
        .collectFirst {
          case name if allVars.contains(name) => name
        }
      if (scatterVar.nonEmpty) {
        scatterVar.get
      } else if (allVars.contains(depName)) {
        if (allVars(depName).kind == VarKind.Output && allVars(depName).kind != VarKind.Output) {
          throw new Exception(
              s"Non-output variable ${varName} depends on output variable ${depName}"
          )
        }
        depName
      } else {
        val exprStr = expr.map(e => s" expression ${TypeUtils.prettyFormatExpr(e)}").getOrElse("")
        throw new Exception(
            s"${varName}${exprStr} references non-task variable ${depName}"
        )
      }
    }

    // Add all missing dependencies to the graph. Any node with no dependencies
    // is linked to root.
    protected def addDependencies(
        names: Iterable[String],
        graph: Graph[String, DiEdge],
        scatterPath: Vector[Int] = Vector.empty
    ): Graph[String, DiEdge] = {
      names.foldLeft(graph) {
        case (g, name) =>
          allVars(name) match {
            case DeclInfo(_, _, Some(expr), _) =>
              val deps = TypeUtils.exprDependencies(expr).keySet.map { dep =>
                (resolveDependency(name, dep, Some(expr)), name, scatterPath)
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
            case _ =>
              // the node has no dependencies, so link it to root
              g ++ Set(GraphUtils.RootNode ~> name)
          }
      }
    }
  }

  case class TaskExprGraphBuilder(inputDefs: Vector[InputParameter] = Vector.empty,
                                  outputDefs: Vector[OutputParameter] = Vector.empty,
                                  privateVariables: Vector[PrivateVariable] = Vector.empty,
                                  commandParts: Vector[Expr] = Vector.empty,
                                  runtime: Map[String, Expr] = Map.empty)
      extends ExprGraphBuilder(inputDefs, outputDefs) {
    override protected lazy val allVars: Map[String, VarInfo] = {
      // collect all variables from task
      val decls: Map[String, VarInfo] = privateVariables.map { decl =>
        decl.name -> DeclInfo(decl, referenced = false, expr = Some(decl.expr), VarKind.Private)
      }.toMap
      inputs ++ outputs ++ decls
    }

    lazy val build: ExprGraph = {
      // Collect required nodes from input, command, runtime, and output blocks
      val commandDeps: Set[String] =
        commandParts.flatMap { expr =>
          TypeUtils.exprDependencies(expr).keySet.map { dep =>
            resolveDependency("command", dep, Some(expr))
          }
        }.toSet
      val runtimeDeps: Set[String] = runtime.values.flatMap { expr =>
        TypeUtils.exprDependencies(expr).keySet.map { dep =>
          resolveDependency("runtime", dep, Some(expr))
        }
      }.toSet
      val requiredNodes =
        inputs.filter(_._2.referenced).keySet | commandDeps | runtimeDeps | outputs.keySet

      // create the graph by iteratively adding missing nodes
      val graph = addDependencies(requiredNodes, Graph.empty[String, DiEdge])

      // Update referenced = true for all vars in the graph.
      // Also update VarKind for decls based on whether they
      // are depended on by any non-output expressions.
      val updatedVars = allVars.map {
        case (name, decl: DeclInfo) if decl.kind == VarKind.Private && graph.contains(name) =>
          val dependentNodes = graph.get(name).outgoing.map(_.to.value)
          val newKind = {
            if (dependentNodes.isEmpty || dependentNodes.exists(n => !outputs.contains(n))) {
              VarKind.Private
            } else {
              throw new Exception(
                  s"private declaration ${decl} depends on the outputs of the command section, which is not allowed"
              )
            }
          }
          name -> decl.copy(referenced = true, kind = newKind)
        case (name, info) if graph.contains(name) =>
          name -> info.setReferenced()
        case other =>
          other
      }

      ExprGraph(graph, updatedVars)
    }
  }

  object TaskExprGraphBuilder {
    def apply(task: Task): TaskExprGraphBuilder = {
      TaskExprGraphBuilder(
          task.inputs,
          task.outputs,
          task.privateVariables,
          task.command.parts,
          task.runtime.map(_.kvs).getOrElse(Map.empty)
      )
    }
  }

  /**
    * Builds a directed dependency graph of variables used within the scope of a task.
    *
    * The graph is rooted by a special root node (`GraphUtils.RootNode`), which is a
    * "dependency" of all required input variables. The graph is constructed iteratively,
    * with all dependencies pointing to the nodes that depend on them, such that if
    * the graph is sorted topologically starting from the root, the nodes are in the
    * order in which they need to be evaluated.
    *
    * @param task the task to graph
    * @return a tuple (graph, vars), where graph is a `Graph` with nodes being variable
    *         names and directed edges, and vars being a mapping of all variable names
    *         to `VarInfo`s.
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
    * In this example, `f` is a required input, so the inital graph is
    * `__root__ ~> f`. Next, to successfully evaluate the `command` and
    * `output` sections, we need variables `name` and `s`, so we add nodes
    * `{f ~> s, s ~> sout, __root__ ~> name}`. Since `b` is optional and
    * not referenced anywhere, it is not added to the graph (though it is
    * still included in the returned `vars` map, with its `referenced`
    * attribute set to `false`.
    */
  def buildFrom(task: Task): ExprGraph = {
    TaskExprGraphBuilder(task).build
  }

  case class CallInfo(element: Call,
                      referenced: Boolean = false,
                      kind: VarKind.Value = VarKind.Private)
      extends VarInfo {
    override def setReferenced(value: Boolean = true): VarInfo = copy(referenced = value)
  }

  case class ScatterInfo(element: Scatter,
                         referenced: Boolean = true,
                         kind: VarKind.Value = VarKind.Input)
      extends VarInfo {
    override def setReferenced(value: Boolean = true): VarInfo = copy(referenced = value)
  }

  case class WorkflowExprGraphBuilder(inputDefs: Vector[InputParameter] = Vector.empty,
                                      outputDefs: Vector[OutputParameter] = Vector.empty,
                                      body: Vector[WorkflowElement])
      extends ExprGraphBuilder(inputDefs, outputDefs) {
    private def getScatterPrefix(scatterPath: Vector[Int]): String = {
      s"${GraphUtils.ScatterNodePrefix}${scatterPath.mkString("_")}_"
    }

    private def createBodyInfos(bodyElements: WorkflowBodyElements,
                                scatterPath: Vector[Int] = Vector.empty): Map[String, VarInfo] = {
      val decls: Map[String, DeclInfo] = bodyElements.privateVariables.map {
        case (name, decl) =>
          name -> DeclInfo(decl, referenced = false, expr = Some(decl.expr), kind = VarKind.Private)
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
            Map(s"${scatterPrefix}${scatter.identifier}" -> ScatterInfo(scatter.scatter))
          scatterVar ++ createBodyInfos(scatter.bodyElements, scatterPath)
      }
      decls ++ calls ++ conditionals ++ scatters
    }

    override lazy val allVars: Map[String, VarInfo] = inputs ++ outputs ++ createBodyInfos(
        WorkflowBodyElements(body)
    )

    private def buildBodyGraph(bodyElements: WorkflowBodyElements,
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
      // scatter blocks get access to the (uniqified) scatter variable that
      // is not visible
      val graphWithScatters = bodyElements.scatters.zipWithIndex.foldLeft(graphWithConditionals) {
        case (graph, (scatter, index)) =>
          buildBodyGraph(scatter.bodyElements, graph, scatterPath :+ index)
      }
      graphWithScatters
    }

    lazy val build: (ExprGraph, WorkflowBodyElements) = {
      // we have to build graph for the nested blocks separately because we need
      // to augment each scatter block with the scatter variable
      val initialNodes = inputs.filter(_._2.referenced).keySet | outputs.keySet
      val initialGraph = addDependencies(initialNodes, Graph.empty[String, DiEdge])
      val bodyElements = WorkflowBodyElements(body)
      val graph = buildBodyGraph(bodyElements, initialGraph)

      // update referenced status of variables
      val updatedVars = allVars.map {
        case (name, info) =>
          name -> info.setReferenced(graph.contains(name))
      }

      (ExprGraph(graph, updatedVars), bodyElements)
    }

    /**
      * Builds the graph and then uses it to break the workflow into blocks
      * of elements that are optimized for parallel execution, using the
      * following rules. Blocks are returned in dependency order.
      *
      * - calls can be grouped together if
      *   - they are independent OR
      *   - they only depend on each other and/or on closure inputs AND
      *   - `groupCalls` is not `Never` AND
      *     - they are all marked as "shortTask" OR
      *     - `groupCalls` is `Always` OR
      *     - `groupCalls` is `Dependent` and the calls are inter-dependent
      * - conditionals can be grouped with calls and/or with each other, so long as
      *   they only contain calls that follow the same rules as above
      * - each scatter is a block by itself
      * - a conditional that contains a nested scatter is in a block by itself
      * - a variable that is only referenced by one block is added to that block
      * - variables referenced by no other blocks, or by 2 or more other blocks
      *   are grouped such that they do not depend on any of the blocks that
      *   depend on them (i.e. there is no circular reference)
      */
    def buildBlocks(): Unit = {}
  }

  object WorkflowExprGraphBuilder {
    def apply(wf: Workflow): WorkflowExprGraphBuilder = {
      WorkflowExprGraphBuilder(wf.inputs, wf.outputs, wf.body)
    }
  }

  /**
    * Builds a dependency graph from a workflow.
    *
    * This works similarly to building a task dependency graph, with the
    * special exception of scatter variables. According to the WDL scoping rules
    * (https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#value-scopes-and-visibility)
    * a scatter variable is only visible within it's block and sub-blocks.
    * Scatter variables are added to the graph, but with a special "uniqified"
    * name. For example:
    *
    *  __scatter__1_2_foo
    *
    * indicates this is second sub-scatter within the first scatter of the workflow,
    * and the scatter variable name is 'foo'.
    * @param wf the workflow
    * @return
    */
  def buildFrom(wf: Workflow): (ExprGraph, WorkflowBodyElements) = {
    WorkflowExprGraphBuilder(wf).build
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
        case Left(cycle) =>
          throw new Exception(s"Graph ${graph} has a cycle at ${cycle}")
        case Right(value) =>
          value.toVector.map(_.value).filterNot(filterNodes.contains)
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
}
