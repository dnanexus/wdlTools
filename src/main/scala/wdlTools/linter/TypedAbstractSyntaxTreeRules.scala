package wdlTools.linter

import java.net.URL
import java.nio.file.Files

import wdlTools.syntax.{TextSource, WdlVersion}
import wdlTools.types.{TypedAbstractSyntax, TypedSyntaxTreeVisitor, Unification}
import wdlTools.types.TypedSyntaxTreeVisitor.VisitorContext
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes._
import wdlTools.types.Util.exprToString
import wdlTools.util.JsonUtil
import wdlTools.util.Util.{stripLeadingWhitespace, writeStringToFile}

import spray.json._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.sys.process._
import scala.util.Random

object TypedAbstractSyntaxTreeRules {
  class LinterTstRule(conf: RuleConf, docSourceUrl: Option[URL], events: mutable.Buffer[LintEvent])
      extends TypedSyntaxTreeVisitor {
    protected def addEvent(ctx: VisitorContext[_ <: Element],
                           message: Option[String] = None): Unit = {
      addEventFromElement(ctx.element, message)
    }

    protected def addEventFromElement(element: Element, message: Option[String] = None): Unit = {
      addEventFromSource(element.text, message)
    }

    protected def addEventFromSource(textSource: TextSource,
                                     message: Option[String] = None): Unit = {
      events.append(LintEvent(conf, textSource, docSourceUrl, message))
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
      ctx.getParent[Element].element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && isQuestionableCoercion(wdlType,
                                                        expr.get.wdlType,
                                                        expectedTypes,
                                                        additionalAllowedCoercions) =>
          addEvent(ctx)
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
                  ctx,
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
              addEvent(ctx, Some(s"${to} argument of ${funcName}() = ${from}"))
          }
        case ExprArray(values, _, _) =>
          // mixed string and non-string types in array
          val types = values.map(_.wdlType)
          if (types.contains(T_String) && !types.forall(stringTypes.contains)) {
            addEvent(ctx, Some(""))
          }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue)
            if isQuestionableCoercion(toTypes(name), fromValue.wdlType, expectedTypes) =>
          addEvent(ctx)
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
        ctx.getParent[Element].element match {
          case Declaration(_, wdlType, expr, _)
              if expr.isDefined && isQuestionableCoercion(wdlType,
                                                          expr.get.wdlType,
                                                          expectedTypes) =>
            addEvent(ctx)
        }

        ctx.element match {
          case ExprApply(funcName, prototype, elements, _, _) =>
            funcName match {
              case "size" =>
                // calling size() with File?/Array[File?]
                elements(0).wdlType match {
                  case T_Optional(_) => addEvent(ctx, Some("File? argument of size()"))
                  case T_Array(T_Optional(_), _) =>
                    addEvent(ctx, Some("Array[File?] argument of size()"))
                  case _ => ()
                }
              case _ =>
                // using a String expression for a File-typed function parameter
                val (argTypes, _) = getSignature(prototype)
                argTypes.zip(elements.map(_.wdlType)).foreach {
                  case (to, from) if isQuestionableCoercion(to, from, expectedTypes) =>
                    addEvent(ctx, Some(s"${to} argument of ${funcName}() = ${from}"))
                }
            }
          case _ => ()
        }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      super.visitCall(ctx)
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue)
            if isQuestionableCoercion(toTypes(name), fromValue.wdlType, expectedTypes) =>
          addEvent(ctx)
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
      ctx.getParent[Element].element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && isArrayCoercion(wdlType, expr.get.wdlType) =>
          addEvent(ctx)
        case _ => ()
      }

      ctx.element match {
        case ExprApply(funcName, prototype, elements, _, _) =>
          val (argTypes, _) = getSignature(prototype)
          argTypes.zip(elements.map(_.wdlType)).foreach {
            case (to, from) if isArrayCoercion(to, from) =>
              addEvent(ctx, Some(s"${to} argument of ${funcName}() = ${from}"))
          }
        case _ => ()
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue) if isArrayCoercion(toTypes(name), fromValue.wdlType) =>
          addEvent(ctx)
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
      ctx.getParent[Element].element match {
        case Declaration(_, wdlType, expr, _)
            if expr.isDefined && !(
                unification.isCoercibleTo(wdlType, expr.get.wdlType) ||
                  isArrayCoercion(wdlType, expr.get.wdlType)
            ) =>
          addEvent(ctx)
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
                addEvent(ctx, Some(s"${to} argument of ${funcName}() = ${from}"))
            }
          case ExprAdd(a, b, _, _) =>
            val aOpt = isOptional(a.wdlType)
            val bOpt = isOptional(b.wdlType)
            val inPlaceholder = ctx.findAncestor[ExprCompoundString].isDefined
            if ((aOpt || bOpt) && !inPlaceholder && !(aOpt && a.wdlType == T_String) && !(bOpt && b.wdlType == T_String)) {
              addEvent(ctx, Some(s"infix operator has operands ${a} and ${b}"))
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
                addEvent(ctx, Some(s"infix operator has operands ${a} and ${b}"))
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
            addEvent(ctx)
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

    private def isNonemptyCoercion(toType: T, fromType: T): Boolean = {
      (toType, fromType) match {
        case (T_Array(_, toNonEmpty), T_Array(_, fromNonEmpty)) if toNonEmpty && !fromNonEmpty =>
          true
        case (T_Map(toKey, toValue), T_Map(fromKey, fromValue)) =>
          isNonemptyCoercion(toKey, fromKey) || isNonemptyCoercion(toValue, fromValue)
        case (T_Pair(toLeft, toRight), T_Pair(fromLeft, fromRight)) =>
          isNonemptyCoercion(toLeft, fromLeft) || isNonemptyCoercion(toRight, fromRight)
        case _ => false
      }
    }

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      val ignoreDecl = ctx.element match {
        case ExprApply(funcName, prototype, elements, _, _) =>
          val (argTypes, _) = getSignature(prototype)
          argTypes.zip(elements.map(_.wdlType)).foreach {
            case (to, from) if isNonemptyCoercion(to, from) => addEvent(ctx)
          }
          ignoreFunctions.contains(funcName)
        case _ => false
      }

      if (!ignoreDecl) {
        ctx.getParent[Element].element match {
          case Declaration(_, wdlType, expr, _)
              if expr.isDefined && isNonemptyCoercion(wdlType, expr.get.wdlType) =>
            addEvent(ctx)
        }
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      val toTypes: Map[String, T] = ctx.element.callee.input.map(x => x._1 -> x._2._1)
      ctx.element.inputs.foreach {
        case (name, fromValue) if isNonemptyCoercion(toTypes(name), fromValue.wdlType) =>
          addEvent(ctx)
      }
    }
  }

  /**
    * Flag unused non-output declarations
    *
    * TODO: enable configuration of heurisitics - rather than disable the rule, the
    *  user can specify patterns to ignore
    */
  case class UnusedDeclarationRule(conf: RuleConf,
                                   version: WdlVersion,
                                   unification: Unification,
                                   events: mutable.Buffer[LintEvent],
                                   docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    // map of callables to declarations
    private val declarations: mutable.Map[Callable, mutable.Buffer[Element]] = mutable.HashMap.empty
    // map of callables to references
    private val referrers: mutable.Map[String, mutable.Set[String]] = mutable.HashMap.empty
    private val indexSuffixes = Set(
        "index",
        "indexes",
        "indices",
        "idx",
        "tbi",
        "bai",
        "crai",
        "csi",
        "fai",
        "dict"
    )

    override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
      super.visitDeclaration(ctx)

      if (ctx.findAncestor[OutputSection].isEmpty) {
        // declaration is not in an OutputSection
        val parent = ctx.findAncestorExecutable.get
        if (!declarations.contains(parent)) {
          declarations(parent) = mutable.ArrayBuffer.empty
        }
        declarations(parent).append(ctx.element)
      }
    }

    override def visitCall(ctx: VisitorContext[Call]): Unit = {
      super.visitCall(ctx)

      if (ctx.element.callee.output.nonEmpty) {
        // ignore call to callable with no outputs
        val parent = ctx.findAncestorExecutable.get
        if (!declarations.contains(parent)) {
          declarations(parent) = mutable.ArrayBuffer.empty
        }
        declarations(parent).append(ctx.element)
      }
    }

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      ctx.element match {
        case ExprIdentifier(id, _, _) =>
          val parentName = ctx.findAncestorExecutable.get.name
          if (!referrers.contains(parentName)) {
            referrers(parentName) = mutable.HashSet.empty
          }
          referrers(parentName).add(id)
        case _ => super.traverseExpression(ctx)
      }
    }

    override def visitDocument(ctx: VisitorContext[Document]): Unit = {
      super.visitDocument(ctx)

      // heuristics to ignore certain declarations
      def ignoreDeclaration(declaration: TypedAbstractSyntax.Declaration,
                            parent: Callable): Boolean = {
        // File whose name suggests it's an hts index file; as these commonly need to
        // be localized, but not explicitly used in task command
        val isFileType = declaration.wdlType match {
          case T_File                       => true
          case T_Array(t, _) if t == T_File => true
          case _                            => false
        }
        if (isFileType && indexSuffixes.exists(suf => declaration.name.endsWith(suf))) {
          return true
        }
        // dxWDL "native" task stubs, which declare inputs but leave command empty
        parent match {
          case t: Task =>
            t.meta.exists(meta => meta.kvs.contains("type") && meta.kvs.contains("id"))
          case _ => false
        }
      }

      // check all declarations within each parent callable to see if they have any referrers
      val wf = ctx.element.workflow
      val wfHasOutputs = wf.forall(_.output.isDefined)
      declarations.foreach {
        case (parent, decls) =>
          val refs = referrers.get(parent.name)
          decls.foreach {
            case d: Declaration
                if refs.isEmpty || !(refs.get.contains(d.name) || ignoreDeclaration(d, parent)) =>
              addEventFromElement(d, Some("declaration"))
            case c: Call if refs.isEmpty || (!wf.contains(parent) || wfHasOutputs) =>
              addEventFromElement(c, Some("call"))
          }
      }
    }
  }

  /**
    * Flags declarations like T? x = :T: where the right-hand side can't be null.
    * The optional quantifier is unnecessary except within a task/workflow
    * input section (where it denotes that the default value can be overridden
    * by expressly passing null). Another exception is File? outputs of tasks,
    * e.g. File? optional_file_output = "filename.txt"
    */
  case class UnnecessaryQuantifierRule(conf: RuleConf,
                                       version: WdlVersion,
                                       unification: Unification,
                                       events: mutable.Buffer[LintEvent],
                                       docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    override def visitDeclaration(ctx: VisitorContext[Declaration]): Unit = {
      ctx.element match {
        case Declaration(_, T_Optional(declType), Some(expr), _)
            if ctx.findAncestor[InputSection].isEmpty && (expr.wdlType match {
              case T_Optional(_) => false
              case _             => true
            }) && (declType match {
              case T_File | T_Directory =>
                val parent = ctx.findAncestor[OutputSection]
                parent.isDefined && parent.get.findAncestor[Task].nonEmpty
              case _ => false
            }) =>
          addEvent(ctx)
      }
    }
  }

  /**
    * If ShellCheck is installed, run it on task commands and propagate any
    * lint it finds.
    * we suppress
    *   SC1083 This {/} is literal
    *   SC2043 This loop will only ever run once for a constant value
    *   SC2050 This expression is constant
    *   SC2157 Argument to -n is always true due to literal strings
    *   SC2193 The arguments to this comparison can never be equal
    * which can be triggered by dummy values we substitute to write the script
    * also SC1009 and SC1072 are non-informative commentary
    */
  case class ShellCheckRule(conf: RuleConf,
                            version: WdlVersion,
                            unification: Unification,
                            events: mutable.Buffer[LintEvent],
                            docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    private val suppressions = Set(1009, 1072, 1083, 2043, 2050, 2157, 2193)
    private val suppressionsStr = suppressions.mkString(",")
    private val shellCheckPath =
      conf.options.get("path").map(JsonUtil.getString).getOrElse("shellcheck")

    if (ShellCheckRule.shellCheckAvailable.isEmpty) {
      ShellCheckRule.shellCheckAvailable = Some(Seq("which", shellCheckPath).! == 0)
    }

    override def visitCommandSection(ctx: VisitorContext[CommandSection]): Unit = {
      if (ctx.element.parts.isEmpty) {
        return
      }
      if (ShellCheckRule.shellCheckAvailable.get) {
        // convert the command block to a String, substituting dummy variables for placeholders
        def callback(expr: Expr): Option[String] = {
          @tailrec
          def getDummyVal(t: T, textSource: TextSource): Option[String] = {
            t match {
              case T_Array(itemType, _) => getDummyVal(itemType, textSource)
              case T_Boolean            => Some("false")
              case other                =>
                // estimate the length of the interpolation in the original source, so that
                // shellcheck will see the same column numbers. + 3 accounts for "~{" and "}"
                val desiredLength = math.max(1, textSource.endCol - textSource.col) + 3
                Some(other match {
                  case T_Int | T_Float => "1" * desiredLength
                  case _               => Random.nextString(desiredLength)
                })
            }
          }
          expr match {
            case ExprPlaceholderEqual(_, _, _, wdlType, text) => getDummyVal(wdlType, text)
            case ExprPlaceholderDefault(_, _, wdlType, text)  => getDummyVal(wdlType, text)
            case ExprPlaceholderSep(_, _, wdlType, text)      => getDummyVal(wdlType, text)
            case ExprIdentifier(_, wdlType, text)             => getDummyVal(wdlType, text)
            case _                                            => None
          }
        }
        val (offsetLine, offsetCol, script) = stripLeadingWhitespace(
            ctx.element.parts.map(x => exprToString(x, Some(callback))).mkString("")
        )
        val tempFile = Files.createTempFile("shellcheck", ".sh")
        val shellCheckCommand = Seq(
            shellCheckPath,
            "-s",
            "bash",
            "-f",
            "json1",
            "-e",
            suppressionsStr,
            tempFile.toString
        )
        try {
          writeStringToFile(script, tempFile, overwrite = true)
          val commandStart = ctx.element.parts.head.text
          shellCheckCommand.!!.parseJson match {
            case JsArray(elements) =>
              elements.foreach { lint =>
                val fields = JsonUtil.getFields(lint)
                val line = JsonUtil.getInt(fields("line"))
                val col = JsonUtil.getInt(fields("column"))
                val code = JsonUtil.getString(fields("code"))
                val msg = JsonUtil.getString(fields("message"))
                val docLine = commandStart.line + offsetLine + line - 1
                val lineOffsetCol = if (line == 1) {
                  commandStart.col + offsetCol
                } else {
                  offsetCol
                }
                val docCol = lineOffsetCol + col - 1
                addEventFromSource(TextSource(
                                       docLine,
                                       docCol,
                                       docLine,
                                       docCol
                                   ),
                                   Some(s"SC${code} ${msg}"))
              }
            case other => throw new Exception(s"Unexpected ShellCheck output ${other}")
          }
        } finally {
          tempFile.toFile.delete()
        }
      }
    }
  }

  object ShellCheckRule {
    var shellCheckAvailable: Option[Boolean] = None
  }

  /**
    * Application of select_first or select_all on a non-optional array.
    */
  case class SelectArrayRule(conf: RuleConf,
                             version: WdlVersion,
                             unification: Unification,
                             events: mutable.Buffer[LintEvent],
                             docSourceUrl: Option[URL])
      extends LinterTstRule(conf, docSourceUrl, events) {
    private val selectFuncNames = Set("select_first", "select_all")

    override def visitExpression(ctx: VisitorContext[Expr]): Unit = {
      ctx.element match {
        case ExprApply(funcName, _, elements, _, _) if selectFuncNames.contains(funcName) =>
          elements.head.wdlType match {
            case T_Array(T_Optional(_), _) => ()
            case _                         => addEvent(ctx, Some(funcName))
          }
        case _ => traverseExpression(ctx)
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
      "T006" -> UnusedDeclarationRule.apply,
      "T007" -> UnnecessaryQuantifierRule.apply,
      "T008" -> ShellCheckRule.apply,
      "T009" -> SelectArrayRule.apply
  )
}
