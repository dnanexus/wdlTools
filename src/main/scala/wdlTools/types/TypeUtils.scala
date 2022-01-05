package wdlTools.types

import wdlTools.syntax.{Operator, Quoting}
import wdlTools.types.WdlTypes._
import wdlTools.types.{TypedAbstractSyntax => TAT}
import dx.util.AbstractBindings

case class DocumentElements(doc: TAT.Document) {
  val (imports, structDefs, tasks) = doc.elements.foldLeft(
      (Vector.empty[TAT.ImportDoc], Vector.empty[TAT.StructDefinition], Vector.empty[TAT.Task])
  ) {
    case ((imports, structs, tasks), element: TAT.ImportDoc) =>
      (imports :+ element, structs, tasks)
    case ((imports, structs, tasks), element: TAT.StructDefinition) =>
      (imports, structs :+ element, tasks)
    case ((imports, structs, tasks), element: TAT.Task) =>
      (imports, structs, tasks :+ element)
    case other => throw new RuntimeException(s"Invalid document element ${other}")
  }
}

sealed trait WorkflowBlock {
  def inputs: Map[String, WdlTypes.T]
  def outputs: Map[String, WdlTypes.T]
}

case class WorkflowScatter(scatter: TAT.Scatter, scatterVars: Set[String]) extends WorkflowBlock {
  val identifier: String = scatter.identifier
  val expr: TypedAbstractSyntax.Expr = scatter.expr
  val bodyElements: WorkflowBodyElements =
    WorkflowBodyElements(scatter.body, scatterVars ++ Set(identifier))

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

case class WorkflowConditional(conditional: TAT.Conditional, scatterVars: Set[String])
    extends WorkflowBlock {
  val expr: TypedAbstractSyntax.Expr = conditional.expr
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(conditional.body, scatterVars)

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

case class WorkflowBodyElements(body: Vector[TAT.WorkflowElement],
                                scatterVars: Set[String] = Set.empty)
    extends WorkflowBlock {
  val (privateVariables, calls, scatters, conditionals) = body.foldLeft(
      (Map.empty[String, TAT.PrivateVariable],
       Map.empty[String, TAT.Call],
       Vector.empty[WorkflowScatter],
       Vector.empty[WorkflowConditional])
  ) {
    case ((declarations, calls, scatters, conditionals), decl: TAT.PrivateVariable)
        if !declarations.contains(decl.name) =>
      (declarations + (decl.name -> decl), calls, scatters, conditionals)
    case (_, decl: TAT.PrivateVariable) =>
      throw new Exception(s"duplicate private variable ${decl}")
    case ((declarations, calls, scatters, conditionals), call: TAT.Call)
        if !calls.contains(call.actualName) =>
      (declarations, calls + (call.actualName -> call), scatters, conditionals)
    case (_, call: TAT.Call) =>
      throw new Exception(s"duplicate call ${call}")
    case ((declarations, calls, scatters, conditionals), scatter: TAT.Scatter) =>
      (declarations, calls, scatters :+ WorkflowScatter(scatter, scatterVars), conditionals)
    case ((declarations, calls, scatters, conditionals), cond: TAT.Conditional) =>
      (declarations, calls, scatters, conditionals :+ WorkflowConditional(cond, scatterVars))
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

case class WdlTypeBindings(bindings: Map[String, T] = Map.empty,
                           override val elementType: String = "type")
    extends AbstractBindings[String, T](bindings) {
  override protected def copyFrom(values: Map[String, T]): WdlTypeBindings = {
    copy(bindings = values)
  }
}

object WdlTypeBindings {
  lazy val empty: WdlTypeBindings = WdlTypeBindings(Map.empty)
}

object TypeUtils {
  val PrimitiveTypes: Set[T] = Set(T_String, T_File, T_Directory, T_Boolean, T_Int, T_Float)

  /**
    * Checks if the right hand side of an assignment matches the left hand side.
    *
    * @param t the type to check
    * @return
    * @todo is null/None primitive?
    * @example
    * Negative examples:
    *   Int i = "hello"
    *   Array[File] files = "8"
    *
    * Positive examples:
    *   Int k =  3 + 9
    *   Int j = k * 3
    *   String s = "Ford model T"
    *   String s2 = 5
    */
  def isPrimitive(t: T): Boolean = PrimitiveTypes.contains(unwrapOptional(t))

  def isPrimitiveValue(expr: TAT.Expr): Boolean = {
    expr match {
      case _: TAT.ValueNone      => true
      case _: TAT.ValueNull      => true
      case _: TAT.ValueBoolean   => true
      case _: TAT.ValueInt       => true
      case _: TAT.ValueFloat     => true
      case _: TAT.ValueString    => true
      case _: TAT.ValueFile      => true
      case _: TAT.ValueDirectory => true
      case _                     => false
    }
  }

  def isOptional(t: T): Boolean = {
    t match {
      case _: T_Optional => true
      case _             => false
    }
  }

  /**
    * Makes a type optional.
    * @param t the type
    * @param force if true, then `t` will be made optional even if it is already optional.
    * @return
    */
  def ensureOptional(t: T, force: Boolean = false): T_Optional = {
    t match {
      case t if force    => T_Optional(t)
      case t: T_Optional => t
      case _             => T_Optional(t)
    }
  }

  def unwrapOptional(t: T, mustBeOptional: Boolean = false): T = {
    t match {
      case T_Optional(wrapped) => wrapped
      case _ if mustBeOptional =>
        throw new Exception(s"Type ${t} is not T_Optional")
      case _ => t
    }
  }

  def prettyFormatType(t: T): String = {
    t match {
      case T_Boolean        => "Boolean"
      case T_Int            => "Int"
      case T_Float          => "Float"
      case T_String         => "String"
      case T_File           => "File"
      case T_Directory      => "Directory"
      case T_Any            => "Any"
      case T_Var(i, _)      => s"Var($i)"
      case T_Identifier(id) => s"Id(${id})"
      case T_Object         => "Object"
      case T_Pair(l, r) =>
        s"Pair[${prettyFormatType(l)}, ${prettyFormatType(r)}]"
      case T_Array(t, nonEmpty) if nonEmpty =>
        s"Array[${prettyFormatType(t)}]+"
      case T_Array(t, _) =>
        s"Array[${prettyFormatType(t)}]"
      case T_Map(k, v) =>
        s"Map[${prettyFormatType(k)}, ${prettyFormatType(v)}]"
      case T_Optional(t) =>
        s"Optional[${prettyFormatType(t)}]"

      // a user defined structure
      case T_Struct(name, _) => s"Struct($name)"

      case T_Task(name, input, output, _) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${prettyFormatType(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${prettyFormatType(t)}"
          }
          .mkString(", ")
        s"TaskSig($name, input=$inputs, outputs=${outputs})"

      case T_Workflow(name, input, output) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${prettyFormatType(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${prettyFormatType(t)}"
          }
          .mkString(", ")
        s"WorkflowSig($name, input={$inputs}, outputs={$outputs})"

      // The type of a call to a task or a workflow.
      case T_Call(name, output: Map[String, T]) =>
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${prettyFormatType(t)}"
          }
          .mkString(", ")
        s"Call $name { $outputs }"

      // T representation for an stdlib function.
      // For example, stdout()
      case T_Function0(name, output) =>
        s"${name}() -> ${prettyFormatType(output)}"

      // A built-in operator with one argument
      case T_Function1(name, input, _) if Operator.All.contains(name) =>
        val symbol = Operator.All(name).symbol
        s"${symbol}${prettyFormatType(input)}"

      // A function with one argument
      case T_Function1(name, input, output) =>
        s"${name}(${prettyFormatType(input)}) -> ${prettyFormatType(output)}"

      // A built-in operator with two arguments
      case T_Function2(name, arg1, arg2, _) if Operator.All.contains(name) =>
        val symbol = Operator.All(name).symbol
        s"${prettyFormatType(arg1)} ${symbol} ${prettyFormatType(arg2)}"

      // A function with two arguments. For example:
      // Float size(File, [String])
      case T_Function2(name, arg1, arg2, output) =>
        s"${name}(${prettyFormatType(arg1)}, ${prettyFormatType(arg2)}) -> ${prettyFormatType(output)}"

      // A function with three arguments. For example:
      // String sub(String, String, String)
      case T_Function3(name, arg1, arg2, arg3, output) =>
        s"${name}(${prettyFormatType(arg1)}, ${prettyFormatType(arg2)}, ${prettyFormatType(arg3)}) -> ${prettyFormatType(output)}"
    }
  }

  /**
    * Renders a WdlTypes.T as a WDL String.
    * @example
    * T_Int ->   "Int"
    * T_Optional[ T_Array[T_Int] ] -> "Array[Int]?"
    * @param t the type
    * @return
    */
  def toWdl(t: T): String = {
    t match {
      // Base case: primitive types.
      case T_Any       => "Any"
      case T_Boolean   => "Boolean"
      case T_Int       => "Int"
      case T_Float     => "Float"
      case T_String    => "String"
      case T_File      => "File"
      case T_Directory => "Directory"

      // compound types
      case T_Array(memberType, _) =>
        val inner = toWdl(memberType)
        s"Array[${inner}]"
      case T_Map(keyType, valueType) =>
        val k = toWdl(keyType)
        val v = toWdl(valueType)
        s"Map[$k, $v]"
      case T_Optional(memberType) =>
        val inner = toWdl(memberType)
        s"$inner?"
      case T_Pair(lType, rType) =>
        val ls = toWdl(lType)
        val rs = toWdl(rType)
        s"Pair[$ls, $rs]"

      // structs
      case T_Struct(structName, _) =>
        structName

      // catch-all for other types not currently supported
      case _ =>
        throw new Exception(s"Unsupported WDL type ${prettyFormatType(t)}")
    }
  }

  /**
    * Utility function for writing an expression in a human readable form
    * @param expr the expression to convert
    * @param callback a function that optionally converts expression to string - if it returns
    *                 Some(string), string is returned rather than the default formatting. This
    *                 can be used to provide custom formatting for some or all parts of a nested
    *                 expression.
    * @param noQuoting whether to disable quoting of strings
    */
  def prettyFormatExpr(expr: TAT.Expr,
                       callback: Option[TAT.Expr => Option[String]] = None,
                       noQuoting: Boolean = false): String = {

    def inner(innerExpr: TAT.Expr, disableQuoting: Boolean): String = {
      val callbackValue = callback.flatMap(_(innerExpr))
      if (callbackValue.isDefined) {
        return callbackValue.get
      }
      innerExpr match {
        case TAT.ValueNull(_)                    => "null"
        case TAT.ValueNone(_)                    => "None"
        case TAT.ValueBoolean(value: Boolean, _) => value.toString
        case TAT.ValueInt(value, _)              => value.toString
        case TAT.ValueFloat(value, _)            => value.toString
        // add double quotes around string-like value unless disableQuoting = true
        case TAT.ValueString(value, _, _) if disableQuoting => value
        case TAT.ValueString(value, _, Quoting.None)        => value
        case TAT.ValueString(value, _, Quoting.Single)      => s"""'$value'"""
        case TAT.ValueString(value, _, Quoting.Double)      => s""""$value""""
        case TAT.ValueFile(value, _) =>
          if (disableQuoting) value else s""""$value""""
        case TAT.ValueDirectory(value, _) =>
          if (disableQuoting) value else s""""$value""""
        case TAT.ExprIdentifier(id: String, _) => id
        case TAT.ExprCompoundString(value, _, quoting) =>
          val vec = value.map(x => inner(x, disableQuoting)).mkString(", ")
          s"ExprCompoundString(${vec}; quoting=${quoting})"
        case TAT.ExprPair(l, r, _) =>
          s"(${inner(l, disableQuoting)}, ${inner(r, disableQuoting)})"
        case TAT.ExprArray(value, _) =>
          "[" + value.map(x => inner(x, disableQuoting)).mkString(", ") + "]"
        case TAT.ExprMap(value, _) =>
          val m2 = value
            .map {
              case (k, v) => s"${inner(k, disableQuoting)} : ${inner(v, disableQuoting)}"
            }
            .mkString(", ")
          s"{$m2}"
        case TAT.ExprObject(value, _) =>
          val m2 = value
            .map {
              case (k, v) =>
                s"${inner(k, disableQuoting = true)} : ${inner(v, disableQuoting)}"
            }
            .mkString(", ")
          s"object {$m2}"
        // ~{true="--yes" false="--no" boolean_value}
        case TAT.ExprPlaceholder(t, f, sep, default, value, _) =>
          val optStr = Vector(
              t.map(e => s"true=${inner(e, disableQuoting)}"),
              f.map(e => s"false=${inner(e, disableQuoting)}"),
              sep.map(e => s"sep=${inner(e, disableQuoting)}"),
              default.map(e => s"default=${inner(e, disableQuoting)}")
          ).flatten.mkString(" ")
          s"{${optStr} ${inner(value, disableQuoting)}}"
        // Access an array element at [index]
        case TAT.ExprAt(array, index, _) =>
          s"${inner(array, disableQuoting)}[${inner(index, disableQuoting)}]"
        // conditional: if (x == 1) then "Sunday" else "Weekday"
        case TAT.ExprIfThenElse(cond, tBranch, fBranch, _) =>
          s"if (${inner(cond, disableQuoting)}) then ${inner(tBranch, disableQuoting)} else ${inner(fBranch, disableQuoting)}"
        // Apply a builtin unary operator
        case TAT.ExprApply(oper: String, _, Vector(unaryValue), _) if Operator.All.contains(oper) =>
          val symbol = Operator.All(oper).symbol
          s"${symbol}${inner(unaryValue, disableQuoting)}"
        // Apply a buildin binary operator
        case TAT.ExprApply(oper: String, _, Vector(lhs, rhs), _) if Operator.All.contains(oper) =>
          val symbol = Operator.All(oper).symbol
          s"${inner(lhs, disableQuoting)} ${symbol} ${inner(rhs, disableQuoting)}"
        // Apply a standard library function to arguments. For example: read_int("4")
        case TAT.ExprApply(funcName, _, elements, _) =>
          val args = elements.map(x => inner(x, disableQuoting)).mkString(", ")
          s"${funcName}(${args})"
        case TAT.ExprGetName(e, id: String, _) => s"${inner(e, disableQuoting)}.${id}"
        case other                             => throw new Exception(s"unexpected expression ${other}")
      }
    }
    inner(expr, noQuoting)
  }

  def prettyFormatInput(input: TAT.InputParameter, indent: String = ""): String = {
    input match {
      case TAT.RequiredInputParameter(name, wdlType) =>
        s"${indent}${prettyFormatType(wdlType)} ${name}"
      case TAT.OverridableInputParameterWithDefault(name, wdlType, defaultExpr) =>
        s"${indent}${prettyFormatType(wdlType)} ${name} = ${prettyFormatExpr(defaultExpr)}"
      case TAT.OptionalInputParameter(name, wdlType) =>
        s"${indent}${prettyFormatType(wdlType)} ${name}"
    }
  }

  def prettyFormatOutput(output: TAT.OutputParameter, indent: String = ""): String = {
    s"${indent}${prettyFormatType(output.wdlType)} ${output.name} = ${prettyFormatExpr(output.expr)}"
  }

  /**
    * Extract all identifiers in an expression.
    * TODO: factor out an ExpressionWalker class.
    * @param expr an expression
    * @return a Map of identifier -> type for all identifiers referenced in `expr`
    * @example
    *    expression   inputs
    *    x + y        Vector(x, y)
    *    x + y + z    Vector(x, y, z)
    *    foo.y + 3    Vector(foo.y)
    *    1 + 9        Vector.empty
    *    "a" + "b"    Vector.empty
    */
  def exprDependencies(expr: TAT.Expr): Map[String, T] = {
    expr match {
      case _ if isPrimitiveValue(expr)     => Map.empty
      case _: TAT.ValueNull                => Map.empty
      case _: TAT.ValueNone                => Map.empty
      case TAT.ExprIdentifier(id, wdlType) => Map(id -> wdlType)
      case TAT.ExprCompoundString(valArr, _, _) =>
        valArr.flatMap(elem => exprDependencies(elem)).toMap
      case TAT.ExprPair(l, r, _)    => exprDependencies(l) ++ exprDependencies(r)
      case TAT.ExprArray(arrVal, _) => arrVal.flatMap(elem => exprDependencies(elem)).toMap
      case TAT.ExprMap(valMap, _) =>
        valMap.flatMap { case (k, v) => exprDependencies(k) ++ exprDependencies(v) }
      case TAT.ExprObject(fields, _) => fields.flatMap { case (_, v) => exprDependencies(v) }
      case TAT.ExprPlaceholder(t, f, sep, default, value: TAT.Expr, _) =>
        Vector(t.map(exprDependencies),
               f.map(exprDependencies),
               sep.map(exprDependencies),
               default.map(exprDependencies)).flatten.flatten.toMap ++ exprDependencies(value)
      // Access an array element at [index]
      case TAT.ExprAt(value, index, _) => exprDependencies(value) ++ exprDependencies(index)
      // conditional
      case TAT.ExprIfThenElse(cond, tBranch, fBranch, _) =>
        exprDependencies(cond) ++ exprDependencies(tBranch) ++ exprDependencies(fBranch)
      // Apply a standard library function to arguments.
      // TODO: some arguments may be _optional_ we need to take that
      // into account. We need to look into the function type
      // and figure out which arguments are optional.
      case TAT.ExprApply(_, _, elements, _) => elements.flatMap(exprDependencies).toMap
      // Access a field in a call
      //   Int z = eliminateDuplicate.fields
      case TAT.ExprGetName(TAT.ExprIdentifier(id, wdlType), _, _) => Map(id -> wdlType)
      case TAT.ExprGetName(expr, _, _)                            => exprDependencies(expr)
      case other =>
        throw new Exception(s"unexpected expression ${other}")
    }
  }

  def collectStructs(wdlTypes: Vector[WdlTypes.T]): Map[String, WdlTypes.T_Struct] = {
    def inner(wdlType: WdlTypes.T,
              structs: Map[String, WdlTypes.T_Struct]): Map[String, WdlTypes.T_Struct] = {
      wdlType match {
        case struct: WdlTypes.T_Struct if !structs.contains(struct.name) =>
          struct.members.values.foldLeft(structs + (struct.name -> struct)) {
            case (accu, memberType) => inner(memberType, accu)
          }
        case WdlTypes.T_Optional(c: WdlTypes.T_Collection) => inner(c, structs)
        case WdlTypes.T_Array(c: WdlTypes.T_Collection, _) => inner(c, structs)
        case WdlTypes.T_Map(_, c: WdlTypes.T_Collection)   => inner(c, structs)
        case WdlTypes.T_Pair(l: WdlTypes.T_Collection, r: WdlTypes.T_Collection) =>
          inner(r, inner(l, structs))
        case WdlTypes.T_Pair(l: WdlTypes.T_Collection, _) => inner(l, structs)
        case WdlTypes.T_Pair(_, r: WdlTypes.T_Collection) => inner(r, structs)
        case _                                            => structs
      }
    }
    wdlTypes.foldLeft(Map.empty[String, WdlTypes.T_Struct]) {
      case (accu, wdlType) => inner(wdlType, accu)
    }
  }
}
