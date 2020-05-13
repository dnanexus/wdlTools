package wdlTools.linter

import java.net.URL

import wdlTools.linter.Severity.Severity
import wdlTools.syntax.WdlVersion
import wdlTools.types.{Stdlib, TypedAbstractSyntax, TypedSyntaxTreeVisitor}
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.WdlTypes._

import scala.collection.mutable

object TstRules {
  class LinterTstRule(id: String,
                      severity: Severity,
                      docSourceUrl: Option[URL],
                      events: mutable.Buffer[LintEvent])
      extends TypedSyntaxTreeVisitor {
    protected def addEvent(element: TypedAbstractSyntax.Element,
                           message: Option[String] = None): Unit = {
      events.append(LintEvent(id, severity, element.text, docSourceUrl, message))
    }
  }

  type LinterTstRuleApplySig = (
      String,
      Severity,
      WdlVersion,
      Stdlib,
      mutable.Buffer[LintEvent],
      Option[URL]
  ) => LinterTstRule

  // rules ported from miniwdl

  /**
    * Conversion from non-string-typed expression to string declaration/parameter
    */
  case class StringCoercionRule(id: String,
                                severity: Severity,
                                version: WdlVersion,
                                stdlib: Stdlib,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterTstRule(id, severity, docSourceUrl, events) {

    private val stringTypes: Set[T] = Set(T_String, T_File, T_Directory)
    private val stringFunctions: Set[String] = Set(
        "stdout",
        "stderr",
        "glob",
        "length",
        "sub",
        "defined",
        "sep",
        "write_lines",
        "write_tsv",
        "write_map",
        "write_json",
        "read_int",
        "read_boolean",
        "read_string",
        "read_float",
        "read_map",
        "read_lines",
        "read_tsv",
        "read_json"
    )

    private def isQuestionableCoercion(to: T, from: T): Boolean = {
      (to, from) match {
        case (T_Array(toType, _), T_Array(fromType, _)) =>
          isQuestionableCoercion(toType, fromType)
        case (T_Map(toKey, toValue), T_Map(fromKey, fromValue)) =>
          isQuestionableCoercion(toKey, fromKey) || isQuestionableCoercion(toValue, fromValue)
        case (T_Pair(toLeft, toRight), T_Pair(fromLeft, fromRight)) =>
          isQuestionableCoercion(toLeft, fromLeft) || isQuestionableCoercion(toRight, fromRight)
        case (T_String, T_String) => false
        case (T_String, _)        => true
        case _                    => false
      }
    }

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      // File-to-String coercions are normal in tasks, but flagged at the workflow level.
      val isInWorkflow = ctx.getParentExecutable match {
        case Some(_: Workflow) => true
        case _                 => false
      }
      if (isInWorkflow) {
        // if this expression is the rhs of a declaration, check that it is coercible to the lhs type
        ctx.getParent.element match {
          case Declaration(_, wdlType, expr, _)
              if expr.isDefined && isQuestionableCoercion(wdlType, expr.get.wdlType) =>
            addEvent(ctx.element)
          case _ => ()
        }
        // check compatible arguments for operations that take multiple string arguments
        ctx.element match {
          case ExprAdd(a, b, _, _) if ctx.findParent[ExprCompoundString].isEmpty =>
            // if either a or b is a non-literal string type while the other is a non-string type
            if (!Vector(a, b).exists(_.isInstanceOf[ValueString])) {
              val wts = Vector(a, b).map(_.wdlType)
              if (wts.contains(T_String) && !wts.forall(stringTypes.contains)) {
                addEvent(
                    ctx.element,
                    Some(
                        "string concatenation (+) has non-string argument; consider using interpolation"
                    )
                )
              }
            }
          case ExprApply(funcName, elements, _, _) if stringFunctions.contains(funcName) =>
            // conversion of non-string expression to string-type function parameter
            val sigs = stdlib.getSignatures(funcName)
            val fromTypes = elements.map(_.wdlType)
            sigs.foreach {
              case (toTypes, _) =>
                toTypes.zip(fromTypes).foreach {
                  case (to, from) if isQuestionableCoercion(to, from) => addEvent(ctx.element)
                }
            }
          case ExprArray(values, _, _) =>
            // mixed string and non-string types in array
            val types = values.map(_.wdlType)
            if (types.contains(T_String) && !types.forall(stringTypes.contains)) {
              addEvent(ctx.element, Some(""))
            }
        }
      }
    }
  }

  val allRules: Map[String, LinterTstRuleApplySig] = Map()
}
