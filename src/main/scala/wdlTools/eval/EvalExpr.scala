package wdlTools.eval

import java.net.URL

import wdlTools.eval.WdlValues._
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.TextSource
import wdlTools.util.{ExprEvalConfig, Options}

case class EvalExpr(opts: Options,
                    evalCfg: ExprEvalConfig,
                    docSourceURL: Option[URL]) {

  private def toString(value : WV, text : TextSource) : String = {
    value match {
      case WV_String(s) => s
      case WV_File(s) => s
      case other => throw new EvaluationException(s"bad value ${other}", text, docSourceURL)
    }
  }

  def compareEqeq(a : Expr, b : Expr, text : TextSource) : Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null) => true
      case (WV_Boolean(b1), WV_Boolean(b2)) => b1 == b2
      case (WV_Int(i1), WV_Int(i2)) => i1 == i2
      case (WV_Float(x1), WV_Float(x2)) => x1 == x2
      case (WV_String(s1), WV_String(s2)) => s1 == s2
      case (WV_File(p1), WV_File(p2)) => p1 == p2

      case (WV_Pair(l1, r1), WV_Pair(l2, r2)) =>
        compareEqeq(l1, l2) && compareEqeq(r1, r2)

        // arrays
      case (WV_Array(a1), WV_Array(a2)) if a1.size != a2.size => false
      case (WV_Array(a1), WV_Array(a2)) =>
        // All array elements must be equal
        (a1 zip a2).forall{
          case (x, y) => compareEqeq(x, y)
        }

        // maps
      case (WV_Map(m1), WV_Map(m2)) if m1.size != m2.size => false
      case (WV_Map(m1), WV_Map(m2)) =>
        val keysEqual = (m1.keys.toSet zip m2.keys.toSet).forall{
          case (k1, k2) => compareEqeq(k1, k2)
        }
        if (!keysEqual) {
          false
        } else {
          // now we know the keys are all equal
          m1.keys.forAll {
            case k =>  compareEqeq(m1(k), m2(k))
          }
        }

        // optionals
      case (WV_Optional(v1), WV_Optional(v2)) =>
        compareEqeq(v1, v2)
      case (WV_Optional(v1), v2) =>
        compareEqeq(v1, v2)
      case (v1, WV_Optional(v2)) =>
        compareEqeq(v1, v2)

      // structs
      case (WV_Struct(name1, _), WV_Struct(name2, _)) if name1 != name2 => false
      case (WV_Struct(_, members1), WV_Struct(_, members2)) if members1.keys.toSet != members2.keys.toSet =>
        // We need the type definition here. The other option is to assume it has already
        // been cleared at compile time.
        throw new Exception(s"error: two structs ${name1} do not have the same number of members")
      case (WV_Struct(_, members1), WV_Struct(_, members2)) =>
        members1.keys.forall{
          case k => compareEqeq(members1(k), members2(k))
        }

      case (_ : WV_Object, _ : WV_Object) =>
        throw new Exception("objects not implemented")
    }
  }

  def compareLt(a : WV, b : WV) : Boolean = {
    (a, b) match {
      case (WV_Null, WV_Null) => false
      case (WV_Int(i1), WV_Int(i2)) => i1 < i2
      case (WV_Float(x1), WV_Float(x2)) => x1 < x2
      case (WV_String(s1), WV_String(s2)) => s1 < s2
      case (WV_File(p1), WV_File(p2)) => p1 < p2
      case (_, _) =>
        throw new EvaluationException(
          s"bad value ${other}, should be a boolean", expr.text, docSourceURL)
    }
  }

  def apply(expr : Expr) : WdlValues.WV = {
    expr match {
      // concatenate an array of strings inside a command block
      case ExprCompoundString(vec: Vector[Expr], _) =>
        val strArray : Vector[String] = vec.map{
          x => toString(apply(x), x.text)
        }
        WV_String(strArray.mkString(""))

      // ~{true="--yes" false="--no" boolean_value}
      case ExprPlaceholderEqual(t: Expr, f: Expr, boolExpr: Expr, _) =>
        apply(boolExpr) match {
          case WV_Boolean(true) => apply(t)
          case WV_Boolean(false) => apply(f)
          case other =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", expr.text, docSourceURL)
        }

      // ~{default="foo" optional_value}
      case ExprPlaceholderDefault(defaultVal: Expr, optVal: Expr, _) =>
        apply(optVal) match {
          case WV_Null => apply(defaultVal)
          case other => other
        }

      // ~{sep=", " array_value}
      case ExprPlaceholderSep(sep: Expr, arrayVal: Expr, _) =>
        val sep2 = toString(apply(sep), sep.text)
        apply(arrayVal) match {
          case WV_Array(ar) =>
            val elements : Vector[String] = ar.map {
              x => toString(x, expr.text)
            }
            WV_String(elements.mkString(sep2))
          case other =>
            throw new EvaluationException(
              s"bad value ${other}, should be a string", expr.text, docSourceURL)
        }

      // operators on one argument
      case ExprUniraryPlus(e, _) =>
        apply(e) match {
          case WV_Float(f) => WV_Float(f)
          case WV_Int(k) => WV_Int(k)
          case other =>
            throw new EvaluationException(
              s"bad value ${other}, should be a number", expr.text, docSourceURL)
        }

      case ExprUniraryMinus(e, _) =>
        apply(e) match {
          case WV_Float(f) => WV_Float(-1 * f)
          case WV_Int(k) => WV_Int(-1 * k)
          case other =>
            throw new EvaluationException(
              s"bad value ${other}, should be a number", expr.text, docSourceURL)
        }

      case ExprNegate(e, _) =>
        apply(e) match {
          case WV_Boolean(b) => WV_Boolean(!b)
          case other =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", expr.text, docSourceURL)
        }

        // operators on two arguments
      case ExprLor(a, b, _) =>
        (apply(a), apply(b)) match {
          case (WV_Boolean(a1), WV_Boolean(b1)) =>
            WV_Boolean(a1 || b1)
          case (WV_Boolean(_), other) =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", b.text, docSourceURL)
          case (other, _) =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", a.text, docSourceURL)
        }

      case ExprLand(a, b, _) =>
        (apply(a), apply(b)) match {
          case (WV_Boolean(a1), WV_Boolean(b1)) =>
            WV_Boolean(a1 && b1)
          case (WV_Boolean(_), other) =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", b.text, docSourceURL)
          case (other, _) =>
            throw new EvaluationException(
              s"bad value ${other}, should be a boolean", a.text, docSourceURL)
        }

        // recursive comparison
      case ExprEqeq(a, b, _) =>
        WV_Boolean(compareEqeq(a, b))
      case ExprNeq(a, b, _) =>
        WV_Boolean(!compareEqeq(a, b))

      case ExprLt(a, b, _) => compareLt(CMP_LT)
      case ExprLte(a, b, _) => compareLte(CMP_LTE)
      case ExprGt(a, b, _) => compareOp(CMP_GT)
      case ExprGte(a, b, _ ) => compareOp(CMP_GTE)

  case class ExprAdd(a: Expr, b: Expr, text: TextSource) extends Expr
  case class ExprSub(a: Expr, b: Expr, text: TextSource) extends Expr
  case class ExprMod(a: Expr, b: Expr, text: TextSource) extends Expr
  case class ExprMul(a: Expr, b: Expr, text: TextSource) extends Expr
  case class ExprDivide(a: Expr, b: Expr, text: TextSource) extends Expr

  // Access an array element at [index]
  case class ExprAt(array: Expr, index: Expr, text: TextSource) extends Expr

  // conditional:
  // if (x == 1) then "Sunday" else "Weekday"
  case class ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, text: TextSource) extends Expr

  // Apply a standard library function to arguments. For example:
  //   read_int("4")
  case class ExprApply(funcName: String, elements: Vector[Expr], text: TextSource) extends Expr

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  case class ExprGetName(e: Expr, id: String, text: TextSource) extends Expr
 } */
    }
  }
}
