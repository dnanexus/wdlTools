package wdlTools.types

import scalax.collection.GraphEdge.DiEdge
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import wdlTools.syntax.WdlVersion
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes.T_Pair
import wdlTools.types.WdlTypes.{T, T_Array, T_Map, T_Optional, T_Struct}

case class ElementNode(element: Element)

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

object ExprGraph {
  object TaskVarKind extends Enumeration {
    val Input, PreCommand, PostCommand, Output = Value
  }

  case class TaskVarInfo(v: Variable,
                         referenced: Boolean,
                         expr: Option[Expr] = None,
                         kind: Option[TaskVarKind.Value] = None)

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
  def buildFromTask(
      task: Task
  ): (Graph[String, DiEdge], Map[String, TaskVarInfo]) = {
    // collect all variables from task
    val inputs: Map[String, TaskVarInfo] = task.inputs.map {
      case req: RequiredInputDefinition =>
        req.name -> TaskVarInfo(req, referenced = true, kind = Some(TaskVarKind.Input))
      case opt: OptionalInputDefinition =>
        opt.name -> TaskVarInfo(opt, referenced = false, kind = Some(TaskVarKind.Input))
      case optWithDefault: OverridableInputDefinitionWithDefault =>
        optWithDefault.name -> TaskVarInfo(optWithDefault,
                                           referenced = false,
                                           expr = Some(optWithDefault.defaultExpr),
                                           kind = Some(TaskVarKind.Input))
    }.toMap
    val outputs: Map[String, TaskVarInfo] = task.outputs.map { out =>
      out.name -> TaskVarInfo(out,
                              referenced = true,
                              expr = Some(out.expr),
                              kind = Some(TaskVarKind.Output))
    }.toMap
    val decls: Map[String, TaskVarInfo] = task.declarations.map { decl =>
      decl.name -> TaskVarInfo(decl, referenced = false, expr = decl.expr)
    }.toMap
    val allVars: Map[String, TaskVarInfo] = inputs ++ outputs ++ decls

    // Since a task is self-contained, it is an error to reference an identifier
    // that is not in the set of task variables.
    def checkDependency(varName: String, depName: String, expr: Option[Expr]): Unit = {
      if (!allVars.contains(depName)) {
        val exprStr = expr.map(e => s" expression ${e}").getOrElse("")
        throw new Exception(
            s"${varName}${exprStr} references non-task variable ${depName}"
        )
      }
    }

    // Add all missing dependencies to the graph. Any node with no dependencies
    // is linked to root.
    def addDependencies(names: Iterable[String],
                        graph: Graph[String, DiEdge]): Graph[String, DiEdge] = {
      names.foldLeft(graph) {
        case (g, name) =>
          allVars(name) match {
            case TaskVarInfo(_, _, Some(expr), _) =>
              val deps = Utils.exprDependencies(expr).keySet.map { dep =>
                checkDependency(name, dep, Some(expr))
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
            case _ =>
              // the node has no dependencies, so link it to root
              g ++ Set(GraphUtils.RootNode ~> name)
          }
      }
    }

    // Collect required nodes from input, command, runtime, and output blocks
    val commandDeps: Set[String] =
      task.command.parts.flatMap { expr =>
        Utils.exprDependencies(expr).keySet.map { dep =>
          checkDependency("command", dep, Some(expr))
          dep
        }
      }.toSet
    val runtimeDeps: Set[String] = task.runtime
      .map(_.kvs.values.flatMap { expr =>
        Utils.exprDependencies(expr).keySet.map { dep =>
          checkDependency("runtime", dep, Some(expr))
          dep
        }
      }.toSet)
      .getOrElse(Set.empty)
    val requiredNodes =
      inputs.filter(_._2.referenced).keySet | commandDeps | runtimeDeps | outputs.keySet

    // create the graph by iteratively adding missing nodes
    val graph = addDependencies(requiredNodes, Graph.empty[String, DiEdge])

    // Update referenced = true for all vars in the graph.
    // Also update VarKind for decls based on whether they are depended on by any non-output
    // expressions.
    val updatedVars = allVars.map {
      case (name, TaskVarInfo(v, _, expr, None)) if graph.contains(name) =>
        val dependentNodes = graph.get(name).outgoing.map(_.to.value)
        val newKind = {
          if (dependentNodes.isEmpty || dependentNodes.exists(n => !outputs.contains(n))) {
            Some(TaskVarKind.PreCommand)
          } else {
            Some(TaskVarKind.PostCommand)
          }
        }
        name -> TaskVarInfo(v, referenced = true, expr = expr, kind = newKind)
      case (name, info) if graph.contains(name) =>
        name -> info.copy(referenced = true)
      case (name, varInfo) if varInfo.referenced =>
        name -> varInfo.copy(referenced = false)
      case other => other
    }

    (graph, updatedVars)
  }
//
//  object WorkflowVarKind extends Enumeration {
//    val Input, Output = Value
//  }
//
//  case class WorkflowVarInfo(v: Variable,
//                             referenced: Boolean,
//                             expr: Option[Expr] = None,
//                             kind: Option[WorkflowVarKind.Value] = None)
//
//  def buildFromWorkflow(wf: Workflow): (Graph[String, DiEdge], Map[String, WorkflowVarInfo]) = {
//    val wfBody = WorkflowBodyElements(wf.body)
//    val inputs: Map[String, TaskVarInfo] = wf.inputs.map {
//      case req: RequiredInputDefinition =>
//        req.name -> TaskVarInfo(req, referenced = true, kind = Some(TaskVarKind.Input))
//      case opt: OptionalInputDefinition =>
//        opt.name -> TaskVarInfo(opt, referenced = false, kind = Some(TaskVarKind.Input))
//      case optWithDefault: OverridableInputDefinitionWithDefault =>
//        optWithDefault.name -> TaskVarInfo(optWithDefault,
//                                           referenced = false,
//                                           expr = Some(optWithDefault.defaultExpr),
//                                           kind = Some(TaskVarKind.Input))
//    }.toMap
//    val outputs: Map[String, TaskVarInfo] = wf.outputs.map { out =>
//      out.name -> TaskVarInfo(out,
//                              referenced = true,
//                              expr = Some(out.expr),
//                              kind = Some(TaskVarKind.Output))
//    }.toMap
//    val decls: Map[String, TaskVarInfo] = wfBody.declarations.map { decl =>
//      decl.name -> TaskVarInfo(decl, referenced = false, expr = decl.expr)
//    }.toMap
//    val allVars: Map[String, TaskVarInfo] = inputs ++ outputs ++ decls
//  }
}

object GraphUtils {
  val RootNode: String = "__root__"

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
    graph.get(root).withSubgraph().topologicalSort(ignorePredecessors = true) match {
      case Left(cycle) =>
        throw new Exception(s"Graph ${graph} has a cycle at ${cycle}")
      case Right(value) =>
        value.toVector.map(_.value).filterNot(filterNodes.contains)
    }
  }
}
