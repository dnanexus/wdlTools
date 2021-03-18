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
        case ValueNone(_)                    => "None"
        case ValueString(value, _)           => value
        case ValueBoolean(value: Boolean, _) => value.toString
        case ValueInt(value, _)              => value.toString
        case ValueFloat(value, _)            => value.toString
        case ExprIdentifier(id: String, _)   => id

        case ExprCompoundString(value: Vector[Expr], _) =>
          val vec = value.map(x => inner(x)).mkString(", ")
          s"CompoundString(${vec})"
        case ExprPair(l, r, _) => s"(${inner(l)}, ${inner(r)})"
        case ExprArray(value: Vector[Expr], _) =>
          "[" + value.map(x => inner(x)).mkString(", ") + "]"
        case ExprMember(key, value, _) =>
          s"${inner(key)} : ${inner(value)}"
        case ExprMap(value: Vector[ExprMember], _) =>
          val m = value
            .map(x => inner(x))
            .mkString(", ")
          "{ " + m + " }"
        case ExprObject(value: Vector[ExprMember], _) =>
          val m = value
            .map(x => inner(x))
            .mkString(", ")
          s"object {$m}"
        case ExprStruct(name, members: Vector[ExprMember], _) =>
          val m = members
            .map(x => inner(x))
            .mkString(", ")
          s"${name} {$m}"
        case ExprPlaceholder(t, f, sep, default, value, _) =>
          val optStr = Vector(
              t.map(e => s"true=${inner(e)}"),
              f.map(e => s"false=${inner(e)}"),
              sep.map(e => s"sep=${inner(e)}"),
              default.map(e => s"default=${inner(e)}")
          ).flatten.mkString(" ")
          s"{${optStr} ${inner(value)}"

        // Access an array element at [index]
        case ExprAt(array: Expr, index: Expr, _) =>
          s"${inner(array)}[${index}]"

        // conditional:
        // if (x == 1) then "Sunday" else "Weekday"
        case ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, _) =>
          s"if (${inner(cond)}) then ${inner(tBranch)} else ${inner(fBranch)}"

        // Apply a builtin unary operator
        case ExprApply(funcName: String, Vector(unaryValue), _)
            if Operator.All.contains(funcName) =>
          val symbol = Operator.All(funcName).symbol
          s"${symbol}${inner(unaryValue)}"
        // Apply a buildin binary operator
        case ExprApply(funcName: String, Vector(lhs, rhs), _) if Operator.All.contains(funcName) =>
          val symbol = Operator.All(funcName).symbol
          s"${inner(lhs)} ${symbol} ${inner(rhs)}"

        // Apply a standard library function to arguments. For example:
        //   read_int("4")
        case ExprApply(funcName: String, elements: Vector[Expr], _) =>
          val args = elements.map(x => inner(x)).mkString(", ")
          s"${funcName}(${args})"

        case ExprGetName(e: Expr, id: String, _) =>
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
      case MetaValueNull(_)                    => "null"
      case MetaValueString(value, _)           => value
      case MetaValueBoolean(value: Boolean, _) => value.toString
      case MetaValueInt(value, _)              => value.toString
      case MetaValueFloat(value, _)            => value.toString
      case MetaValueArray(value, _) =>
        "[" + value.map(x => prettyFormatMetaValue(x, callback)).mkString(", ") + "]"
      case MetaValueObject(value, _) =>
        val m = value
          .map(x => s"${x.id} : ${prettyFormatMetaValue(x.value, callback)}")
          .mkString(", ")
        s"{$m}"
    }
  }
}
