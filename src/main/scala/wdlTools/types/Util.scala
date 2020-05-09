package wdlTools.types

import java.net.URL

import wdlTools.util.Options
import WdlTypes._

object Util {
  // check if the right hand side of an assignment matches the left hand side
  //
  // Negative examples:
  //    Int i = "hello"
  //    Array[File] files = "8"
  //
  // Positive examples:
  //    Int k =  3 + 9
  //    Int j = k * 3
  //    String s = "Ford model T"
  //    String s2 = 5
  def isPrimitive(t: T): Boolean = {
    t match {
      case T_String | T_File | T_Boolean | T_Int | T_Float => true
      case _                                               => false
    }
  }

  def typeToString(t: T): String = {
    t match {
      case T_Boolean        => "Boolean"
      case T_Int            => "Int"
      case T_Float          => "Float"
      case T_String         => "String"
      case T_File           => "File"
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

  // Utility function for writing an expression in a human readable form
  def exprToString(expr: Expr): String = {
    expr match {
      case ValueNull(_, _)                    => "null"
      case ValueString(value, _, _)           => value
      case ValueFile(value, _, _)             => value
      case ValueBoolean(value: Boolean, _, _) => value.toString
      case ValueInt(value, _, _)              => value.toString
      case ValueFloat(value, _, _)            => value.toString
      case ExprIdentifier(id: String, _, _)   => id

      case ExprCompoundString(value: Vector[Expr], _, _) =>
        val vec = value.map(exprToString).mkString(", ")
        s"ExprCompoundString(${vec})"
      case ExprPair(l, r, _, _) => s"(${exprToString(l)}, ${exprToString(r)})"
      case ExprArray(value: Vector[Expr], _, _) =>
        "[" + value.map(exprToString).mkString(", ") + "]"
      case ExprMap(value: Vector[ExprMapItem], _, _) =>
        val m = value
          .map(exprToString)
          .mkString(", ")
        "{ " + m + " }"
      case ExprMapItem(key, value, _, _) =>
        s"${exprToString(key)} : ${exprToString(value)}"
      case ExprObject(value: Vector[ExprObjectMember], _, _) =>
        val m = value
          .map(exprToString)
          .mkString(", ")
        s"object($m)"
      case ExprObjectMember(key, value, _, _) =>
        s"${key} : ${exprToString(value)}"
      // ~{true="--yes" false="--no" boolean_value}
      case ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr, _, _) =>
        s"{true=${exprToString(t)} false=${exprToString(f)} ${exprToString(value)}"

      // ~{default="foo" optional_value}
      case ExprPlaceholderDefault(default: Expr, value: Expr, _, _) =>
        s"{default=${exprToString(default)} ${exprToString(value)}}"

      // ~{sep=", " array_value}
      case ExprPlaceholderSep(sep: Expr, value: Expr, _, _) =>
        s"{sep=${exprToString(sep)} ${exprToString(value)}"

      // operators on one argument
      case ExprUniraryPlus(value: Expr, _, _) =>
        s"+ ${exprToString(value)}"
      case ExprUniraryMinus(value: Expr, _, _) =>
        s"- ${exprToString(value)}"
      case ExprNegate(value: Expr, _, _) =>
        s"not(${exprToString(value)})"

      // operators on two arguments
      case ExprLor(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} || ${exprToString(b)}"
      case ExprLand(a: Expr, b: Expr, _, _)   => s"${exprToString(a)} && ${exprToString(b)}"
      case ExprEqeq(a: Expr, b: Expr, _, _)   => s"${exprToString(a)} == ${exprToString(b)}"
      case ExprLt(a: Expr, b: Expr, _, _)     => s"${exprToString(a)} < ${exprToString(b)}"
      case ExprGte(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} >= ${exprToString(b)}"
      case ExprNeq(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} != ${exprToString(b)}"
      case ExprLte(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} <= ${exprToString(b)}"
      case ExprGt(a: Expr, b: Expr, _, _)     => s"${exprToString(a)} > ${exprToString(b)}"
      case ExprAdd(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} + ${exprToString(b)}"
      case ExprSub(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} - ${exprToString(b)}"
      case ExprMod(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} % ${exprToString(b)}"
      case ExprMul(a: Expr, b: Expr, _, _)    => s"${exprToString(a)} * ${exprToString(b)}"
      case ExprDivide(a: Expr, b: Expr, _, _) => s"${exprToString(a)} / ${exprToString(b)}"

      // Access an array element at [index]
      case ExprAt(array: Expr, index: Expr, _, _) =>
        s"${exprToString(array)}[${index}]"

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, _, _) =>
        s"if (${exprToString(cond)}) then ${exprToString(tBranch)} else ${exprToString(fBranch)}"

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case ExprApply(funcName: String, elements: Vector[Expr], _, _) =>
        val args = elements.map(exprToString).mkString(", ")
        s"${funcName}(${args})"

      case ExprGetName(e: Expr, id: String, _, _) =>
        s"${exprToString(e)}.${id}"
    }
  }
}
