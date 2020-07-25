package wdlTools.types

import scalax.collection.GraphEdge.DiEdge
import scalax.collection.Graph
import scalax.collection.GraphPredef._
import wdlTools.types.TypedAbstractSyntax._

//case class ElementGraph() {
//  private var graph = Graph.empty[Element, DiEdge]
//}

object ExprGraph {
  object VarKind extends Enumeration {
    val Input, PreCommand, PostCommand, Output = Value
  }

  case class VarInfo(v: Variable,
                     referenced: Boolean,
                     expr: Option[Expr] = None,
                     kind: Option[VarKind.Value] = None)

  /**
    * Build a directed dependency graph of variables used within the scope of a task.
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
    * ```wdl
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
    * ```
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
  ): (Graph[String, DiEdge], Map[String, VarInfo]) = {
    // collect all variables from task
    val inputs: Map[String, VarInfo] = task.inputs.map {
      case req: RequiredInputDefinition =>
        req.name -> VarInfo(req, referenced = true, kind = Some(VarKind.Input))
      case opt: OptionalInputDefinition =>
        opt.name -> VarInfo(opt, referenced = false, kind = Some(VarKind.Input))
      case optWithDefault: OverridableInputDefinitionWithDefault =>
        optWithDefault.name -> VarInfo(optWithDefault,
                                       referenced = false,
                                       expr = Some(optWithDefault.defaultExpr),
                                       kind = Some(VarKind.Input))
    }.toMap
    val outputs: Map[String, VarInfo] = task.outputs.map { out =>
      out.name -> VarInfo(out,
                          referenced = true,
                          expr = Some(out.expr),
                          kind = Some(VarKind.Output))
    }.toMap
    val decls: Map[String, VarInfo] = task.declarations.map { decl =>
      decl.name -> VarInfo(decl, referenced = false, expr = decl.expr)
    }.toMap
    val allVars: Map[String, VarInfo] = inputs ++ outputs ++ decls

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
            case VarInfo(_, _, Some(expr), _) =>
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
      case (name, VarInfo(v, _, expr, None)) if graph.contains(name) =>
        val dependentNodes = graph.get(name).outgoing.map(_.to.value)
        val newKind = {
          if (dependentNodes.isEmpty || dependentNodes.exists(n => !outputs.contains(n))) {
            Some(VarKind.PreCommand)
          } else {
            Some(VarKind.PostCommand)
          }
        }
        name -> VarInfo(v, referenced = true, expr = expr, kind = newKind)
      case (name, info) if graph.contains(name) =>
        name -> info.copy(referenced = true)
      case (name, varInfo) if varInfo.referenced =>
        name -> varInfo.copy(referenced = false)
      case other => other
    }

    (graph, updatedVars)
  }
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
