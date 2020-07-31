package wdlTools.types

import wdlTools.types.WdlTypes._
import wdlTools.types.{TypedAbstractSyntax => TAT}

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
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(scatter.body)
}

case class WorkflowConditional(conditional: TAT.Conditional) {
  val bodyElements: WorkflowBodyElements = WorkflowBodyElements(conditional.body)
}

case class WorkflowBodyElements(body: Vector[TAT.WorkflowElement]) {
  val (declarations, calls, scatters, conditionals) = body.foldLeft(
      (Vector.empty[TAT.Declaration],
       Vector.empty[TAT.Call],
       Vector.empty[WorkflowScatter],
       Vector.empty[WorkflowConditional])
  ) {
    case ((declarations, calls, scatters, conditionals), decl: TAT.Declaration) =>
      (declarations :+ decl, calls, scatters, conditionals)
    case ((declarations, calls, scatters, conditionals), call: TAT.Call) =>
      (declarations, calls :+ call, scatters, conditionals)
    case ((declarations, calls, scatters, conditionals), scatter: TAT.Scatter) =>
      (declarations, calls, scatters :+ WorkflowScatter(scatter), conditionals)
    case ((declarations, calls, scatters, conditionals), cond: TAT.Conditional) =>
      (declarations, calls, scatters, conditionals :+ WorkflowConditional(cond))
  }
}

object Utils {

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
  def isPrimitive(t: T): Boolean = {
    t match {
      case T_String | T_File | T_Directory | T_Boolean | T_Int | T_Float => true
      case _                                                             => false
    }
  }

  def isPrimitiveValue(expr: TAT.Expr): Boolean = {
    expr match {
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
  def makeOptional(t: T, force: Boolean = false): T_Optional = {
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

  def typeToString(t: T): String = {
    t match {
      case T_Boolean        => "Boolean"
      case T_Int            => "Int"
      case T_Float          => "Float"
      case T_String         => "String"
      case T_File           => "File"
      case T_Directory      => "Directory"
      case T_Any            => "Any"
      case T_Var(i)         => s"Var($i)"
      case T_Identifier(id) => s"Id(${id})"
      case T_Pair(l, r)     => s"Pair[${typeToString(l)}, ${typeToString(r)}]"
      case T_Array(t, _)    => s"Array[${typeToString(t)}]"
      case T_Map(k, v)      => s"Map[${typeToString(k)}, ${typeToString(v)}]"
      case T_Object         => "Object"
      case T_Optional(t)    => s"Optional[${typeToString(t)}]"

      // a user defined structure
      case T_Struct(name, _) => s"Struct($name)"

      case T_Task(name, input, output) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${typeToString(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${typeToString(t)}"
          }
          .mkString(", ")
        s"TaskSig($name, input=$inputs, outputs=${outputs})"

      case T_Workflow(name, input, output) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${typeToString(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${typeToString(t)}"
          }
          .mkString(", ")
        s"WorkflowSig($name, input={$inputs}, outputs={$outputs})"

      // The type of a call to a task or a workflow.
      case T_Call(name, output: Map[String, T]) =>
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${typeToString(t)}"
          }
          .mkString(", ")
        s"Call $name { $outputs }"

      // T representation for an stdlib function.
      // For example, stdout()
      case T_Function0(name, output) =>
        s"${name}() -> ${typeToString(output)}"

      // A function with one argument
      case T_Function1(name, input, output) =>
        s"${name}(${typeToString(input)}) -> ${typeToString(output)}"

      // A function with two arguments. For example:
      // Float size(File, [String])
      case T_Function2(name, arg1, arg2, output) =>
        s"${name}(${typeToString(arg1)}, ${typeToString(arg2)}) -> ${typeToString(output)}"

      // A function with three arguments. For example:
      // String sub(String, String, String)
      case T_Function3(name, arg1, arg2, arg3, output) =>
        s"${name}(${typeToString(arg1)}, ${typeToString(arg2)}, ${typeToString(arg3)}) -> ${typeToString(output)}"
    }
  }

  /**
    * Utility function for writing an expression in a human readable form
    * @param expr the expression to convert
    * @param callback a function that optionally converts expression to string - if it returns
    *                 Some(string), string is returned rather than the default formatting. This
    *                 can be used to provide custom formatting for some or all parts of a nested
    *                 expression.
    * @param disableQuoting whether to disable quoting of strings
    */
  def exprToString(expr: TAT.Expr,
                   callback: Option[TAT.Expr => Option[String]] = None,
                   disableQuoting: Boolean = false): String = {
    if (callback.isDefined) {
      val s = callback.get(expr)
      if (s.isDefined) {
        return s.get
      }
    }

    expr match {
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
        val vec = value.map(x => exprToString(x, callback)).mkString(", ")
        s"ExprCompoundString(${vec})"
      case TAT.ExprPair(l, r, _, _) =>
        s"(${exprToString(l, callback)}, ${exprToString(r, callback)})"
      case TAT.ExprArray(value, _, _) =>
        "[" + value.map(x => exprToString(x, callback)).mkString(", ") + "]"
      case TAT.ExprMap(value, _, _) =>
        val m2 = value
          .map {
            case (k, v) => s"${exprToString(k, callback)} : ${exprToString(v, callback)}"
          }
          .mkString(", ")
        s"{$m2}"
      case TAT.ExprObject(value, _, _) =>
        val m2 = value
          .map {
            case (k, v) =>
              s"${exprToString(k, callback, disableQuoting = true)} : ${exprToString(v, callback)}"
          }
          .mkString(", ")
        s"object {$m2}"

      // ~{true="--yes" false="--no" boolean_value}
      case TAT.ExprPlaceholderEqual(t, f, value, _, _) =>
        s"{true=${exprToString(t, callback)} false=${exprToString(f, callback)} ${exprToString(value, callback)}"

      // ~{default="foo" optional_value}
      case TAT.ExprPlaceholderDefault(default, value, _, _) =>
        s"{default=${exprToString(default, callback)} ${exprToString(value, callback)}}"

      // ~{sep=", " array_value}
      case TAT.ExprPlaceholderSep(sep, value, _, _) =>
        s"{sep=${exprToString(sep, callback)} ${exprToString(value, callback)}"

      // operators on one argument
      case TAT.ExprUnaryPlus(value, _, _) =>
        s"+ ${exprToString(value, callback)}"
      case TAT.ExprUnaryMinus(value, _, _) =>
        s"- ${exprToString(value, callback)}"
      case TAT.ExprNegate(value, _, _) =>
        s"not(${exprToString(value, callback)})"

      // operators on two arguments
      case TAT.ExprLor(a, b, _, _) =>
        s"${exprToString(a, callback)} || ${exprToString(b, callback)}"
      case TAT.ExprLand(a, b, _, _) =>
        s"${exprToString(a, callback)} && ${exprToString(b, callback)}"
      case TAT.ExprEqeq(a, b, _, _) =>
        s"${exprToString(a, callback)} == ${exprToString(b, callback)}"
      case TAT.ExprLt(a, b, _, _) => s"${exprToString(a, callback)} < ${exprToString(b, callback)}"
      case TAT.ExprGte(a, b, _, _) =>
        s"${exprToString(a, callback)} >= ${exprToString(b, callback)}"
      case TAT.ExprNeq(a, b, _, _) =>
        s"${exprToString(a, callback)} != ${exprToString(b, callback)}"
      case TAT.ExprLte(a, b, _, _) =>
        s"${exprToString(a, callback)} <= ${exprToString(b, callback)}"
      case TAT.ExprGt(a, b, _, _)  => s"${exprToString(a, callback)} > ${exprToString(b, callback)}"
      case TAT.ExprAdd(a, b, _, _) => s"${exprToString(a, callback)} + ${exprToString(b, callback)}"
      case TAT.ExprSub(a, b, _, _) => s"${exprToString(a, callback)} - ${exprToString(b, callback)}"
      case TAT.ExprMod(a, b, _, _) => s"${exprToString(a, callback)} % ${exprToString(b, callback)}"
      case TAT.ExprMul(a, b, _, _) => s"${exprToString(a, callback)} * ${exprToString(b, callback)}"
      case TAT.ExprDivide(a, b, _, _) =>
        s"${exprToString(a, callback)} / ${exprToString(b, callback)}"

      // Access an array element at [index]
      case TAT.ExprAt(array, index, _, _) =>
        s"${exprToString(array, callback)}[${index}]"

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, _) =>
        s"if (${exprToString(cond, callback)}) then ${exprToString(tBranch, callback)} else ${exprToString(fBranch, callback)}"

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case TAT.ExprApply(funcName, _, elements, _, _) =>
        val args = elements.map(x => exprToString(x, callback)).mkString(", ")
        s"${funcName}(${args})"

      case TAT.ExprGetName(e, id: String, _, _) =>
        s"${exprToString(e, callback)}.${id}"
    }
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
      case TAT.ExprPlaceholderEqual(t: TAT.Expr, f: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(t) ++ exprDependencies(f) ++ exprDependencies(value)
      case TAT.ExprPlaceholderDefault(default: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(default) ++ exprDependencies(value)
      case TAT.ExprPlaceholderSep(sep: TAT.Expr, value: TAT.Expr, _, _) =>
        exprDependencies(sep) ++ exprDependencies(value)
      // operators on one argument
      case oper1: TAT.ExprOperator1 =>
        exprDependencies(oper1.value)
      // operators on two arguments
      case oper2: TAT.ExprOperator2 =>
        exprDependencies(oper2.a) ++ exprDependencies(oper2.b)
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
