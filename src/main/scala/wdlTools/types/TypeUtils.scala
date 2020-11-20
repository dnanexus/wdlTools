package wdlTools.types

import wdlTools.syntax.Operator
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

case class WorkflowScatter(scatter: TAT.Scatter) {
  val identifier: String = scatter.identifier
  val expr: TypedAbstractSyntax.Expr = scatter.expr
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(scatter.body)
}

case class WorkflowConditional(conditional: TAT.Conditional) {
  val expr: TypedAbstractSyntax.Expr = conditional.expr
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(conditional.body)
}

case class WorkflowBodyElements(body: Vector[TAT.WorkflowElement]) {
  val (privateVariables, calls, scatters, conditionals) = body.foldLeft(
      (Vector.empty[TAT.PrivateVariable],
       Vector.empty[TAT.Call],
       Vector.empty[WorkflowScatter],
       Vector.empty[WorkflowConditional])
  ) {
    case ((declarations, calls, scatters, conditionals), decl: TAT.PrivateVariable) =>
      (declarations :+ decl, calls, scatters, conditionals)
    case ((declarations, calls, scatters, conditionals), call: TAT.Call) =>
      (declarations, calls :+ call, scatters, conditionals)
    case ((declarations, calls, scatters, conditionals), scatter: TAT.Scatter) =>
      (declarations, calls, scatters :+ WorkflowScatter(scatter), conditionals)
    case ((declarations, calls, scatters, conditionals), cond: TAT.Conditional) =>
      (declarations, calls, scatters, conditionals :+ WorkflowConditional(cond))
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
  // TODO: what about T_Any?
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
        case TAT.ValueNull(_, _)                    => "null"
        case TAT.ValueNone(_, _)                    => "None"
        case TAT.ValueBoolean(value: Boolean, _, _) => value.toString
        case TAT.ValueInt(value, _, _)              => value.toString
        case TAT.ValueFloat(value, _, _)            => value.toString

        // add double quotes around string-like value unless disableQuoting = true
        case TAT.ValueString(value, _, _) =>
          if (disableQuoting) value else s""""$value""""
        case TAT.ValueFile(value, _, _) =>
          if (disableQuoting) value else s""""$value""""
        case TAT.ValueDirectory(value, _, _) =>
          if (disableQuoting) value else s""""$value""""

        case TAT.ExprIdentifier(id: String, _, _) => id

        case TAT.ExprCompoundString(value, _, _) =>
          val vec = value.map(x => inner(x, disableQuoting)).mkString(", ")
          s"ExprCompoundString(${vec})"
        case TAT.ExprPair(l, r, _, _) =>
          s"(${inner(l, disableQuoting)}, ${inner(r, disableQuoting)})"
        case TAT.ExprArray(value, _, _) =>
          "[" + value.map(x => inner(x, disableQuoting)).mkString(", ") + "]"
        case TAT.ExprMap(value, _, _) =>
          val m2 = value
            .map {
              case (k, v) => s"${inner(k, disableQuoting)} : ${inner(v, disableQuoting)}"
            }
            .mkString(", ")
          s"{$m2}"
        case TAT.ExprObject(value, _, _) =>
          val m2 = value
            .map {
              case (k, v) =>
                s"${inner(k, disableQuoting = true)} : ${inner(v, disableQuoting)}"
            }
            .mkString(", ")
          s"object {$m2}"

        // ~{true="--yes" false="--no" boolean_value}
        case TAT.ExprPlaceholderCondition(t, f, value, _, _) =>
          s"{true=${inner(t, disableQuoting)} false=${inner(f, disableQuoting)} ${inner(value, disableQuoting)}}"

        // ~{default="foo" optional_value}
        case TAT.ExprPlaceholderDefault(default, value, _, _) =>
          s"{default=${inner(default, disableQuoting)} ${inner(value, disableQuoting)}}"

        // ~{sep=", " array_value}
        case TAT.ExprPlaceholderSep(sep, value, _, _) =>
          s"{sep=${inner(sep, disableQuoting)} ${inner(value, disableQuoting)}}"

        // Access an array element at [index]
        case TAT.ExprAt(array, index, _, _) =>
          s"${inner(array, disableQuoting)}[${inner(index, disableQuoting)}]"

        // conditional:
        // if (x == 1) then "Sunday" else "Weekday"
        case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, _) =>
          s"if (${inner(cond, disableQuoting)}) then ${inner(tBranch, disableQuoting)} else ${inner(fBranch, disableQuoting)}"

        // Apply a builtin unary operator
        case TAT.ExprApply(oper: String, _, Vector(unaryValue), _, _)
            if Operator.All.contains(oper) =>
          val symbol = Operator.All(oper).symbol
          s"${symbol}${inner(unaryValue, disableQuoting)}"
        // Apply a buildin binary operator
        case TAT.ExprApply(oper: String, _, Vector(lhs, rhs), _, _)
            if Operator.All.contains(oper) =>
          val symbol = Operator.All(oper).symbol
          s"${inner(lhs, disableQuoting)} ${symbol} ${inner(rhs, disableQuoting)}"
        // Apply a standard library function to arguments. For example:
        //   read_int("4")
        case TAT.ExprApply(funcName, _, elements, _, _) =>
          val args = elements.map(x => inner(x, disableQuoting)).mkString(", ")
          s"${funcName}(${args})"

        case TAT.ExprGetName(e, id: String, _, _) =>
          s"${inner(e, disableQuoting)}.${id}"
      }
    }
    inner(expr, noQuoting)
  }

  def prettyFormatInput(input: TAT.InputParameter, indent: String = ""): String = {
    input match {
      case TAT.RequiredInputParameter(name, wdlType, _) =>
        s"${indent}${prettyFormatType(wdlType)} ${name}"
      case TAT.OverridableInputParameterWithDefault(name, wdlType, defaultExpr, _) =>
        s"${indent}${prettyFormatType(wdlType)} ${name} = ${prettyFormatExpr(defaultExpr)}"
      case TAT.OptionalInputParameter(name, wdlType, _) =>
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
      case _ if isPrimitiveValue(expr) =>
        Map.empty
      case _: TAT.ValueNull =>
        Map.empty
      case _: TAT.ValueNone =>
        Map.empty
      case TAT.ExprIdentifier(id, wdlType, _) =>
        Map(id -> wdlType)
      case TAT.ExprCompoundString(valArr, _, _) =>
        valArr.flatMap(elem => exprDependencies(elem)).toMap
      case TAT.ExprPair(l, r, _, _) =>
        exprDependencies(l) ++ exprDependencies(r)
      case TAT.ExprArray(arrVal, _, _) =>
        arrVal.flatMap(elem => exprDependencies(elem)).toMap
      case TAT.ExprMap(valMap, _, _) =>
        valMap.flatMap { case (k, v) => exprDependencies(k) ++ exprDependencies(v) }
      case TAT.ExprObject(fields, _, _) =>
        fields.flatMap { case (_, v) => exprDependencies(v) }
      case TAT.ExprPlaceholderCondition(t: TAT.Expr, f: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(t) ++ exprDependencies(f) ++ exprDependencies(value)
      case TAT.ExprPlaceholderDefault(default: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(default) ++ exprDependencies(value)
      case TAT.ExprPlaceholderSep(sep: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(sep) ++ exprDependencies(value)
      // Access an array element at [index]
      case TAT.ExprAt(value, index, _, _) =>
        exprDependencies(value) ++ exprDependencies(index)
      // conditional:
      case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, _) =>
        exprDependencies(cond) ++ exprDependencies(tBranch) ++ exprDependencies(fBranch)
      // Apply a standard library function to arguments.
      //
      // TODO: some arguments may be _optional_ we need to take that
      // into account. We need to look into the function type
      // and figure out which arguments are optional.
      case TAT.ExprApply(_, _, elements, _, _) =>
        elements.flatMap(exprDependencies).toMap
      // Access a field in a call
      //   Int z = eliminateDuplicate.fields
      case TAT.ExprGetName(TAT.ExprIdentifier(id, wdlType, _), _, _, _) =>
        Map(id -> wdlType)
      case TAT.ExprGetName(expr, _, _, _) =>
        exprDependencies(expr)
    }
  }
}
