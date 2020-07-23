package wdlTools.types

import scalax.collection.GraphEdge.DiEdge
import scalax.collection.Graph
import wdlTools.types.TypedAbstractSyntax._

case class ElementGraph() {
  private var graph = Graph.empty[Element, DiEdge]
}

object ExprGraph {
  object ExprNodeType extends Enumeration {
    val Input, PreCommand, PostCommand, Output = Value
  }

  case class ExprNode(v: Variable,
                      optional: Boolean,
                      expr: Option[Expr] = None,
                      nodeType: Option[ExprNodeType.Value] = None)

  def buildFromTask(task: Task): Graph[String, DiEdge] = {
    // collect all variables from task
    val inputNodes = task.inputs.map {
      case req: RequiredInputDefinition =>
        ExprNode(req, optional = false, nodeType = Some(ExprNodeType.Input))
      case opt: OptionalInputDefinition =>
        ExprNode(opt, optional = true, nodeType = Some(ExprNodeType.Input))
      case optWithDefault: OverridableInputDefinitionWithDefault =>
        ExprNode(optWithDefault,
                 optional = true,
                 expr = Some(optWithDefault.defaultExpr),
                 nodeType = Some(ExprNodeType.Input))
    }
    val outputNodes = task.outputs.map { out =>
      ExprNode(out, optional = false, expr = Some(out.expr), nodeType = Some(ExprNodeType.Output))
    }
    val declNodes = task.declarations.map { decl =>
      ExprNode(decl, optional = false, expr = decl.expr)
    }
    // split by optional
    val optionalNodes, requiredNodes =
      (inputNodes ++ outputNodes ++ declNodes).partition(_.optional)
    // collect all identifiers from the required nodes and add them to the graph
    requiredNodes.flatMap { node =>
    }
    var graph = Graph.empty[String, DiEdge]

  }
}
