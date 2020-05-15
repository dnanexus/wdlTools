package wdlTools.linter

import java.net.URL

import wdlTools.syntax.WdlVersion
import wdlTools.types.{TypedAbstractSyntax, TypedSyntaxTreeVisitor, Unification}
import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes._

import scala.annotation.tailrec
import scala.collection.mutable

object TstRules {
  class LinterTstRule(conf: RuleConf, docSourceUrl: Option[URL], events: mutable.Buffer[LintEvent])
      extends TypedSyntaxTreeVisitor {
    protected def addEvent(element: TypedAbstractSyntax.Element,
                           message: Option[String] = None): Unit = {
      events.append(LintEvent(conf, element.text, docSourceUrl, message))
    }
  }

  type LinterTstRuleApplySig = (
      RuleConf,
      WdlVersion,
      Unification,
      mutable.Buffer[LintEvent],
      Option[URL]
  ) => LinterTstRule

  // rules ported from miniwdl

  def isQuestionableCoercion(toType: T,
                             fromType: T,
                             expectedTypes: Set[T],
                             additionalAllowedCoercions: Set[T] = Set.empty): Boolean = {
    val allowedCoercions = expectedTypes ++ additionalAllowedCoercions ++ Set(T_Any)
    (toType, fromType) match {
      case (T_Array(toType, _), T_Array(fromType, _)) =>
        isQuestionableCoercion(toType, fromType, expectedTypes, additionalAllowedCoercions)
      case (T_Map(toKey, toValue), T_Map(fromKey, fromValue)) =>
        isQuestionableCoercion(toKey, fromKey, expectedTypes, additionalAllowedCoercions) ||
          isQuestionableCoercion(toValue, fromValue, expectedTypes, additionalAllowedCoercions)
      case (T_Pair(toLeft, toRight), T_Pair(fromLeft, fromRight)) =>
        isQuestionableCoercion(toLeft, fromLeft, expectedTypes, additionalAllowedCoercions) ||
          isQuestionableCoercion(toRight, fromRight, expectedTypes, additionalAllowedCoercions)
      case (to, from) if expectedTypes.contains(to) => !allowedCoercions.contains(from)
      case _                                        => false
    }
  }

  /**
    * Returns a Vector of all the signatures for the given function,
    * where a signature is a tuple of (param_types, return_type),
    * where param_types is a Vector of the parameter types.
    * @param prototype the function prototype
    * @return
    */
  def getSignature(prototype: T_Function): (Vector[T], T) = {
    prototype match {
      case T_Function0(_, output)                   => (Vector.empty, output)
      case T_Function1(_, arg1, output)             => (Vector(arg1), output)
      case T_Function2(_, arg1, arg2, output)       => (Vector(arg1, arg2), output)
      case T_Function3(_, arg1, arg2, arg3, output) => (Vector(arg1, arg2, arg3), output)
    }
  }

  /**
    * Coercion from non-string-typed expression to string declaration/parameter
    */
  case class StringCoercionRule(conf: RuleConf,
                                version: WdlVersion,
                                unification: Unification,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {

    private val expectedTypes: Set[T] = Set(T_String)
    private val stringTypes: Set[T] = Set(T_String, T_File, T_Directory)

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      // File-to-String coercions are normal in tasks, but flagged at the workflow level.
      val additionalAllowedCoercions: Set[T] = ctx.findAncestorExecutable match {
        case Some(_: Workflow) => Set.empty
        case _                 => Set(T_File, T_Directory)
      }

      // if this expression is the rhs of a declaration, check that it is coercible to the lhs type
      ctx.getParent.element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && isQuestionableCoercion(wdlType,
                                                        expr.get.wdlType,
                                                        expectedTypes,
                                                        additionalAllowedCoercions) =>
          addEvent(ctx.element)
        case _ => ()
      }
      // check compatible arguments for operations that take multiple string arguments
      ctx.element match {
        case ExprAdd(a, b, _, _) if ctx.findAncestor[ExprCompoundString].isEmpty =>
          if (!Vector(a, b).exists(_.isInstanceOf[ValueString])) {
            // not within a placeholder and either a or b is a non-literal string type while
            // the other is a non-string type
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
        case ExprApply(funcName, prototype, elements, _, _) =>
          // conversion of non-String expression to String-type function parameter
          val (argTypes, _) = getSignature(prototype)
          argTypes.zip(elements.map(_.wdlType)).foreach {
            case (to, from)
                if isQuestionableCoercion(to, from, expectedTypes, additionalAllowedCoercions) =>
              addEvent(ctx.element, Some(s"${to} argument of ${funcName}() = ${from}"))
          }
        case ExprArray(values, _, _) =>
          // mixed string and non-string types in array
          val types = values.map(_.wdlType)
          if (types.contains(T_String) && !types.forall(stringTypes.contains)) {
            addEvent(ctx.element, Some(""))
          }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue)
            if isQuestionableCoercion(toTypes(name), fromValue.wdlType, expectedTypes) =>
          addEvent(ctx.element)
      }
    }
  }

  /**
    * Coercion from string-typed expression to file-typed declaration/parameter
    * that occur somewhere besides task output
    */
  case class FileCoercionRule(conf: RuleConf,
                              version: WdlVersion,
                              unification: Unification,
                              events: mutable.Buffer[LintEvent],
                              docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    private val expectedTypes: Set[T] = Set(T_File, T_Directory)

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      // ignore coercions in output sections
      if (ctx.findAncestor[OutputSection].isEmpty) {
        ctx.getParent.element match {
          case Declaration(_, wdlType, expr, _)
              if expr.isDefined && isQuestionableCoercion(wdlType,
                                                          expr.get.wdlType,
                                                          expectedTypes) =>
            addEvent(ctx.element)
          case _ => ()
        }

        ctx.element match {
          case ExprApply(funcName, prototype, elements, _, _) =>
            funcName match {
              case "size" =>
                // calling size() with File?/Array[File?]
                elements(0).wdlType match {
                  case T_Optional(_) => addEvent(ctx.element, Some("File? argument of size()"))
                  case T_Array(T_Optional(_), _) =>
                    addEvent(ctx.element, Some("Array[File?] argument of size()"))
                }
              case _ =>
                // using a String expression for a File-typed function parameter
                val (argTypes, _) = getSignature(prototype)
                argTypes.zip(elements.map(_.wdlType)).foreach {
                  case (to, from) if isQuestionableCoercion(to, from, expectedTypes) =>
                    addEvent(ctx.element, Some(s"${to} argument of ${funcName}() = ${from}"))
                }
            }
        }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      super.visitCall(ctx)
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue)
            if isQuestionableCoercion(toTypes(name), fromValue.wdlType, expectedTypes) =>
          addEvent(ctx.element)
      }
    }
  }

  private def isArrayCoercion(toType: T, fromType: T): Boolean = {
    @tailrec
    def arrayLevels(t: T, level: Int = 0): Int = {
      t match {
        case T_Array(t, _) => arrayLevels(t, level + 1)
        case _             => level
      }
    }
    (toType, fromType) match {
      case (_, T_Any) | (_, T_Array(T_Any, _))                => false
      case (a: T_Array, b) if arrayLevels(a) > arrayLevels(b) => true
      case _                                                  => false
    }
  }

  /**
    * Implicit promotion of T -> Array[T]
    */
  case class ArrayCoercionRule(conf: RuleConf,
                               version: WdlVersion,
                               unification: Unification,
                               events: mutable.Buffer[LintEvent],
                               docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      ctx.getParent.element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && isArrayCoercion(wdlType, expr.get.wdlType) =>
          addEvent(ctx.element)
        case _ => ()
      }

      ctx.element match {
        case ExprApply(funcName, prototype, elements, _, _) =>
          val (argTypes, _) = getSignature(prototype)
          argTypes.zip(elements.map(_.wdlType)).foreach {
            case (to, from) if isArrayCoercion(to, from) =>
              addEvent(ctx.element, Some(s"${to} argument of ${funcName}() = ${from}"))
          }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue) if isArrayCoercion(toTypes(name), fromValue.wdlType) =>
          addEvent(ctx.element)
      }
    }
  }

  def isOptional(t: T): Boolean = {
    t match {
      case _: T_Optional => true
      case _             => false
    }
  }

  /**
    * Expression of optional type where a non-optional value is expected.
    * These may or may not fail type-checking, depending on the strictness
    * of the type-checking regime and the WDL version.
    */
  case class OptionalCoercionRule(conf: RuleConf,
                                  version: WdlVersion,
                                  unification: Unification,
                                  events: mutable.Buffer[LintEvent],
                                  docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      ctx.getParent.element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && !(
                unification.isCoercibleTo(wdlType, expr.get.wdlType) ||
                  isArrayCoercion(wdlType, expr.get.wdlType)
            ) =>
          addEvent(ctx.element)
        case _ => ()
      }

      // ignore when within `if (defined(x))`
      val inConditionOnDefined = ctx.findAncestor[Conditional].exists { condCtx =>
        condCtx.element.expr match {
          case ExprApply("defined", _, _, _, _) => true
          case _                                => false
        }
      }
      if (!inConditionOnDefined) {
        ctx.element match {
          case ExprApply(funcName, prototype, elements, _, _) =>
            val (argTypes, _) = getSignature(prototype)
            argTypes.zip(elements.map(_.wdlType)).foreach {
              case (to, from)
                  if !(unification.isCoercibleTo(to, from) || isArrayCoercion(to, from)) =>
                addEvent(ctx.element, Some(s"${to} argument of ${funcName}() = ${from}"))
            }
          case ExprAdd(a, b, _, _) =>
            val aOpt = isOptional(a.wdlType)
            val bOpt = isOptional(b.wdlType)
            val inPlaceholder = ctx.findAncestor[ExprCompoundString].isDefined
            if ((aOpt || bOpt) && !inPlaceholder && !(aOpt && a.wdlType == T_String) && !(bOpt && b.wdlType == T_String)) {
              addEvent(ctx.element, Some(s"infix operator has operands ${a} and ${b}"))
            }
          case other =>
            val args = other match {
              case ExprSub(a, b, _, _)    => Some(a, b)
              case ExprMul(a, b, _, _)    => Some(a, b)
              case ExprDivide(a, b, _, _) => Some(a, b)
              case ExprLand(a, b, _, _)   => Some(a, b)
              case ExprLor(a, b, _, _)    => Some(a, b)
              case _                      => None
            }
            if (args.isDefined) {
              val (a, b) = args.get
              if (isOptional(a.wdlType) || isOptional(b.wdlType)) {
                addEvent(ctx.element, Some(s"infix operator has operands ${a} and ${b}"))
              }
            }
        }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue) =>
          val to = toTypes(name)
          val from = fromValue.wdlType
          if (!(unification.isCoercibleTo(to, from) || isArrayCoercion(to, from))) {
            addEvent(ctx.element)
          }
      }
    }
  }

  /**
    * Coercing a possibly-empty array to a declaration or parameter requiring
    * a non-empty array.
    */
  case class NonEmptyCoercionRule(conf: RuleConf,
                                  version: WdlVersion,
                                  unification: Unification,
                                  events: mutable.Buffer[LintEvent],
                                  docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    private val ignoreFunctions = Set("glob", "read_lines", "read_tsv", "read_array")

    private def isNonemtpyCoercion(toType: T, fromType: T): Boolean = {
      (toType, fromType) match {
        case (T_Array(_, toNonEmpty), T_Array(_, fromNonEmpty)) if toNonEmpty && !fromNonEmpty =>
          true
        case (T_Map(toKey, toValue), T_Map(fromKey, fromValue)) =>
          isNonemtpyCoercion(toKey, fromKey) || isNonemtpyCoercion(toValue, fromValue)
        case (T_Pair(toLeft, toRight), T_Pair(fromLeft, fromRight)) =>
          isNonemtpyCoercion(toLeft, fromLeft) || isNonemtpyCoercion(toRight, fromRight)
        case _ => false
      }
    }

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      val ignoreDecl = ctx.element match {
        case ExprApply(funcName, prototype, elements, _, _) =>
          val (argTypes, _) = getSignature(prototype)
          argTypes.zip(elements.map(_.wdlType)).foreach {
            case (to, from) if isNonemtpyCoercion(to, from) => addEvent(ctx.element)
          }
          ignoreFunctions.contains(funcName)
        case _ => false
      }

      if (!ignoreDecl) {
        ctx.getParent.element match {
          case Declaration(_, wdlType, expr, _)
              if expr.isDefined && isNonemtpyCoercion(wdlType, expr.get.wdlType) =>
            addEvent(ctx.element)
          case _ => ()
        }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue) if isNonemtpyCoercion(toTypes(name), fromValue.wdlType) =>
          addEvent(ctx.element)
      }
    }
  }

  /**
    * Call without all required inputs.
    */
  case class IncompleteCallRule(conf: RuleConf,
                                version: WdlVersion,
                                unification: Unification,
                                events: mutable.Buffer[LintEvent],
                                docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val missingRequiredInputs = ctx.element.callee.input
        .filter(x => !x._2._2)
        .view
        .filterKeys(key => !ctx.element.inputs.contains(key))
      if (missingRequiredInputs.nonEmpty) {
        addEvent(ctx.element, Some(missingRequiredInputs.keys.mkString(",")))
      }
    }
  }

  /**
    * Flag unused non-output declarations
    * heuristic exceptions:
    * 1. File whose name suggests it's an hts index file; as these commonly need to
    *    be localized, but not explicitly used in task command
    * 2. dxWDL "native" task stubs, which declare inputs but leave command empty.
    * TODO: enable configuration of heurisitics - rather than disable the rule, the
    *  user can specify patterns to ignore
    */
  case class UnusedDeclarationRule(conf: RuleConf,
                                   version: WdlVersion,
                                   unification: Unification,
                                   events: mutable.Buffer[LintEvent],
                                   docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
      if (ctx.findAncestor[OutputSection].isEmpty) {
        // declaration is not in an OutputSection
        // TODO: need mapping of decl name to referrers
      }
    }
  }

  /**
    * The outputs of a Call are neither used nor propagated.
    */
  case class UnusedCallRule(conf: RuleConf,
                            version: WdlVersion,
                            unification: Unification,
                            events: mutable.Buffer[LintEvent],
                            docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      // ignore call to callable with no outputs
      if (ctx.element.callee.output.nonEmpty) {
        // TODO: need mapping of call to referrers
      }
    }
  }

  // TODO: load these dynamically from a file
  val allRules: Map[String, LinterTstRuleApplySig] = Map(
      "T001" -> StringCoercionRule.apply,
      "T002" -> FileCoercionRule.apply,
      "T003" -> ArrayCoercionRule.apply,
      "T004" -> OptionalCoercionRule.apply,
      "T005" -> NonEmptyCoercionRule.apply,
      "T006" -> IncompleteCallRule.apply,
      "T007" -> UnusedDeclarationRule.apply,
      "T008" -> UnusedCallRule.apply
  )
}
