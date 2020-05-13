package wdlTools.linter

import java.net.URL

import wdlTools.linter.Severity.Severity
import wdlTools.syntax.WdlVersion
import wdlTools.types.{TypedAbstractSyntax, TypedSyntaxTreeVisitor}

import scala.collection.mutable

object TstRules {
  class LinterTypedSyntaxTreeRule(id: String,
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
      mutable.Buffer[LintEvent],
      Option[URL]
  ) => LinterTypedSyntaxTreeRule

  // rules ported from miniwdl

  /**
    * Conversion from File-typed expression to String declaration/parameter
    */
  //  case class StringCoersionRule(id: String,
  //                                severity: Severity,
  //                                version: WdlVersion,
  //                                typesContext: types.Context,
  //                                stdlib: types.Stdlib,
  //                                events: mutable.Buffer[LintEvent],
  //                                docSourceUrl: Option[URL])
  //      extends LinterAstRule(id, severity, docSourceUrl, events) {
  //
  //    // TODO: This can probably be replaced by a call to one of the functions
  //    //  in types.Util
  //    def isCompoundCoercion[T <: Type](to: Type, from: WT)(implicit tag: ClassTag[T]): Boolean = {
  //      (to, from) match {
  //        case (TypeArray(toType, _, _), WT_Array(fromType)) =>
  //          isCompoundCoercion[T](toType, fromType)
  //        case (TypeMap(toKey, toValue, _), WT_Map(fromKey, fromValue)) =>
  //          isCompoundCoercion[T](toKey, fromKey) || isCompoundCoercion[T](toValue, fromValue)
  //        case (TypePair(toLeft, toRight, _), WT_Pair(fromLeft, fromRight)) =>
  //          isCompoundCoercion(toLeft, fromLeft) || isCompoundCoercion(toRight, fromRight)
  //        case (_: T, _: T) => false
  //        case (_: T, _)    => true
  //        case _            => false
  //      }
  //    }
  //    def isCompoundCoercion[T <: WT](to: WT, from: WT)(implicit tag: ClassTag[T]): Boolean = {
  //      (to, from) match {
  //        case (WT_Array(toType), WT_Array(fromType)) =>
  //          isCompoundCoercion[T](toType, fromType)
  //        case (WT_Map(toKey, toValue), WT_Map(fromKey, fromValue)) =>
  //          isCompoundCoercion[T](toKey, fromKey) || isCompoundCoercion[T](toValue, fromValue)
  //        case (WT_Pair(toLeft, toRight), WT_Pair(fromLeft, fromRight)) =>
  //          isCompoundCoercion(toLeft, fromLeft) || isCompoundCoercion(toRight, fromRight)
  //        case (_: T, _: T) => false
  //        case (_: T, _)    => true
  //        case _            => false
  //      }
  //    }
  //
  //    override def visitExpression(ctx: ASTVisitor.Context[Expr]): Unit = {
  //      // File-to-String coercions are normal in tasks, but flagged at the workflow level.
  //      val isInWorkflow = ctx.getParentExecutable match {
  //        case Some(_: Workflow) => true
  //        case _                 => false
  //      }
  //      if (isInWorkflow) {
  //        // if this expression is the rhs of a declaration, check that it is coercible to the lhs type
  //        ctx.getParent.element match {
  //          case Declaration(name, wdlType, expr, _)
  //              if expr.isDefined && isCompoundCoercion[TypeString](
  //                  wdlType,
  //                  typesContext.declarations(name)
  //              ) =>
  //            addEvent(ctx.element)
  //          case _ => ()
  //        }
  //        // check compatible arguments for operations that take multiple string arguments
  //        // TODO: can't implement this until we have type inference
  //        ctx.element match {
  //          case ExprAdd(a, b, _) if ctx.findParent[ExprCompoundString].isEmpty =>
  //            // if either a or b is a non-literal string type while the other is a file type
  //            val wts = Vector(a, b).map(inferType)
  //            val areStrings = wts.map(isStringType)
  //            if (areStrings.exists(_) && !areStrings.forall(_) && !wts.exits(isStringLiteral)) {
  //              addEvent(ctx, Some(""))
  //            }
  //          case ExprApply(funcName, elements, _) =>
  //            // conversion of file-type expression to string-type function parameter
  //            val toTypes: Vector[WT] = stdlib.getFunction(funcName) match {
  //              case WT_Function1(_, arg1, _)             => Vector(arg1)
  //              case WT_Function2(_, arg1, arg2, _)       => Vector(arg1, arg2)
  //              case WT_Function3(_, arg1, arg2, arg3, _) => Vector(arg1, arg2, arg3)
  //              case _                                    => Vector.empty
  //            }
  //            val fromTypes: Vector[WT] = elements.map(inferType)
  //            toTypes.zip(fromTypes).foreach {
  //              case (to, from) if isCompoundCoercion[WT_String](to, from) => addEvent(ctx.element)
  //            }
  //          case ExprArray(values, _) =>
  //            // mixed string and file types in array
  //            val areStrings = values.apply(isStringType)
  //            if (areStrings.exists(_) && !areStrings.forall(_)) {
  //              addEvent(ctx, Some(""))
  //            }
  //        }
  //      }
  //    }
  //  }

  val allRules: Map[String, LinterTstRuleApplySig] = Map()
}
