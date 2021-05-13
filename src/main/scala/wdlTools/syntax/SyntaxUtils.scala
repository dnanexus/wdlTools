package wdlTools.syntax

import AbstractSyntax._

object SyntaxUtils {

  /**
    * Utility function for writing an expression in a human readable form
    * @param expr the expression to convert
    * @param callback a function that optionally converts expression to string - if it returns
    *                 Some(string), string is returned rather than the default formatting. This
    *                 can be used to provide custom formatting for some or all parts of a nested
    *                 expression.
    */
  def prettyFormatExpr(expr: Expr, callback: Option[Expr => Option[String]] = None): String = {
    def inner(innerExpr: Expr): String = {
      val callbackValue = callback.flatMap(_(innerExpr))
      if (callbackValue.isDefined) {
        return callbackValue.get
      }
      innerExpr match {
        case ValueNone()                  => "None"
        case ValueString(value)           => value
        case ValueBoolean(value: Boolean) => value.toString
        case ValueInt(value)              => value.toString
        case ValueFloat(value)            => value.toString
        case ExprIdentifier(id: String)   => id

        case ExprCompoundString(value: Vector[Expr]) =>
          val vec = value.map(x => inner(x)).mkString(", ")
          s"CompoundString(${vec})"
        case ExprPair(l, r) => s"(${inner(l)}, ${inner(r)})"
        case ExprArray(value: Vector[Expr]) =>
          "[" + value.map(x => inner(x)).mkString(", ") + "]"
        case ExprMember(key, value) =>
          s"${inner(key)} : ${inner(value)}"
        case ExprMap(value: Vector[ExprMember]) =>
          val m = value
            .map(x => inner(x))
            .mkString(", ")
          "{ " + m + " }"
        case ExprObject(value: Vector[ExprMember]) =>
          val m = value
            .map(x => inner(x))
            .mkString(", ")
          s"object {$m}"
        case ExprStruct(name, members: Vector[ExprMember]) =>
          val m = members
            .map(x => inner(x))
            .mkString(", ")
          s"${name} {$m}"
        case ExprPlaceholder(t, f, sep, default, value) =>
          val optStr = Vector(
              t.map(e => s"true=${inner(e)}"),
              f.map(e => s"false=${inner(e)}"),
              sep.map(e => s"sep=${inner(e)}"),
              default.map(e => s"default=${inner(e)}")
          ).flatten.mkString(" ")
          s"{${optStr} ${inner(value)}"

        // Access an array element at [index]
        case ExprAt(array: Expr, index: Expr) =>
          s"${inner(array)}[${index}]"

        // conditional:
        // if (x == 1) then "Sunday" else "Weekday"
        case ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr) =>
          s"if (${inner(cond)}) then ${inner(tBranch)} else ${inner(fBranch)}"

        // Apply a builtin unary operator
        case ExprApply(funcName: String, Vector(unaryValue)) if Operator.All.contains(funcName) =>
          val symbol = Operator.All(funcName).symbol
          s"${symbol}${inner(unaryValue)}"
        // Apply a buildin binary operator
        case ExprApply(funcName: String, Vector(lhs, rhs)) if Operator.All.contains(funcName) =>
          val symbol = Operator.All(funcName).symbol
          s"${inner(lhs)} ${symbol} ${inner(rhs)}"

        // Apply a standard library function to arguments. For example:
        //   read_int("4")
        case ExprApply(funcName: String, elements: Vector[Expr]) =>
          val args = elements.map(x => inner(x)).mkString(", ")
          s"${funcName}(${args})"

        case ExprGetName(e: Expr, id: String) =>
          s"${inner(e)}.${id}"
      }
    }
    inner(expr)
  }

  def prettyFormatMetaValue(value: MetaValue,
                            callback: Option[MetaValue => Option[String]] = None): String = {
    if (callback.isDefined) {
      val s = callback.get(value)
      if (s.isDefined) {
        return s.get
      }
    }
    value match {
      case MetaValueNull()                  => "null"
      case MetaValueString(value)           => value
      case MetaValueBoolean(value: Boolean) => value.toString
      case MetaValueInt(value)              => value.toString
      case MetaValueFloat(value)            => value.toString
      case MetaValueArray(value) =>
        "[" + value.map(x => prettyFormatMetaValue(x, callback)).mkString(", ") + "]"
      case MetaValueObject(value) =>
        val m = value
          .map(x => s"${x.id} : ${prettyFormatMetaValue(x.value, callback)}")
          .mkString(", ")
        s"{$m}"
    }
  }
}
