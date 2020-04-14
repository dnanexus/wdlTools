package wdlTools.typing

import wdlTools.syntax.TextSource
import WdlTypes._

// This is the WDL typesystem
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
  private def isPrimitive(t: WT): Boolean = {
    t match {
      case WT_String | WT_File | WT_Boolean | WT_Int | WT_Float => true
      case _                                                    => false
    }
  }

  def isCoercibleTo(left: WT, right: WT): Boolean = {
    (left, right) match {
      case (WT_String, x) if isPrimitive(x) => true
      // A null value is converted to the string "null"
      case (WT_String, WT_Optional(x)) if isPrimitive(x) => true
      case (WT_File, WT_String | WT_File)                => true
      case (WT_Boolean, WT_Boolean)                      => true
      case (WT_Int, WT_Int)                              => true
      case (WT_Float, WT_Int | WT_Float)                 => true

      case (WT_Optional(l), WT_Optional(r)) => isCoercibleTo(l, r)

      // An Int is coercible to Int?
      case (WT_Optional(l), r) => isCoercibleTo(l, r)

      case (WT_Array(l), WT_Array(r))         => isCoercibleTo(l, r)
      case (WT_Map(kl, vl), WT_Map(kr, vr))   => isCoercibleTo(kl, kr) && isCoercibleTo(vl, vr)
      case (WT_Pair(l1, l2), WT_Pair(r1, r2)) => isCoercibleTo(l1, r1) && isCoercibleTo(l2, r2)

      case (WT_Identifier(structNameL), WT_Identifier(structNameR)) =>
        structNameL == structNameR

      case (WT_Object, WT_Object)           => true
      case (WT_Var(i), WT_Var(j)) if i == j => true
      case _                                => false
    }
  }

  // Figure out the most general type that is both x and y.
  //
  // Note: this is very lame implementation where the only option
  // we consider is whether one type is coercible to the other.
  def mostGeneralType(x: WT, y: WT, text: TextSource): WT = {
    if (isCoercibleTo(x, y))
      return x
    if (isCoercibleTo(y, x))
      return y
    throw new TypeUnificationException(s"$x and $y cannot be unified", text)
  }

  // when calling a polymorphic function things get complicated.
  // For example:
  //    select_first([null, 6])
  // The signature for select first is:
  //    Array[X?] -> X
  // we need to figure out that X is Int.
  //
  //
  def unifyPair(x: WT, y: WT, bindings: Map[WT_Var, WT], text: TextSource): Map[WT_Var, WT] = {
    (x, y) match {
      // base case, primitive types
      case (_, _) if (isPrimitive(x) && isPrimitive(y) && isCoercibleTo(x, y)) =>
        bindings
      case (WT_Optional(l), WT_Optional(r)) => unifyPair(l, r, bindings, text)
      case (WT_Optional(l), r)              =>
        // I'm not sure this is such a great idea. We are allowing an X to
        // become an X?
        unifyPair(l, r, bindings, text)

      case (WT_Array(l), WT_Array(r)) => unifyPair(l, r, bindings, text)
      case (WT_Map(k1, v1), WT_Map(k2, v2)) =>
        val bindings1 = unifyPair(k1, k2, bindings, text)
        unifyPair(v1, v2, bindings1, text)
      case (WT_Pair(l1, r1), WT_Pair(l2, r2)) =>
        val bindings1 = unifyPair(l1, l2, bindings, text)
        unifyPair(r1, r2, bindings1, text)
      case (WT_Identifier(l), WT_Identifier(r)) if l == r =>
        bindings
      case (WT_Var(i), WT_Var(j)) if (i == j) =>
        bindings
      case (a: WT_Var, b: WT_Var) =>
        // found a type equality between two variables
        (bindings.get(a), bindings.get(b)) match {
          case (None, None) =>
            bindings + (a -> b)
          case (None, Some(z)) =>
            bindings + (a -> z)
          case (Some(z), None) =>
            bindings + (b -> z)
          case (Some(z), Some(w)) =>
            val gt = mostGeneralType(z, w, text)
            bindings + (a -> gt) + (b -> gt)
        }
      case (a: WT_Var, _) if !(bindings contains a) =>
        // found a new binding for a type variable
        bindings + (a -> y)

      case (a: WT_Var, _) =>
        // a binding already exists, choose the more general
        // type
        val existing = bindings(a)
        val gt = mostGeneralType(existing, y, text)
        bindings + (a -> gt)

      case _ =>
        throw new TypeUnificationException(s"Types $x and $y do not match", text)
    }
  }

  // Unify a set of type pairs, and return a solution for the type
  // variables. If the types cannot be unified throw a TypeUnification exception.
  //
  // For example the signature for zip is:
  //    Array[Pair(X,Y)] zip(Array[X], Array[Y])
  // In order to type check a declaration like:
  //    Array[Pair[Int, String]] x  = zip([1, 2, 3], ["a", "b", "c"])
  // we solve for the X and Y type variables on the right hand
  // side. This should yield: { X : Int, Y : String }
  //
  // The inputs in this example are:
  //    x = [ WT_Array(WT_Var(0)), WT_Array(WT_Var(1)) ]
  //    y = [ WT_Array(WT_Int),  WT_Array(WT_String) ]
  //
  // The result is:
  //    WT_Var(0) -> WT_Int
  //    WT_Var(1) -> WT_String
  //
  def unify(x: Vector[WT], y: Vector[WT], text: TextSource): Map[WT_Var, WT] = {
    val pairs = x zip y
    pairs.foldLeft(Map.empty[WT_Var, WT]) {
      case (bindings, (lt, rt)) =>
        unifyPair(lt, rt, bindings, text)
    }
  }

  // substitute the type variables for the values in type 't'
  def substitute(t: WT, typeBindings: Map[WT_Var, WT], srcText: TextSource): WT = {
    def sub(t: WT): WT = {
      t match {
        case WT_String | WT_File | WT_Boolean | WT_Int | WT_Float => t
        case a: WT_Var if !(typeBindings contains a) =>
          throw new TypeException(s"type variable ${toString(a)} does not have a binding", srcText)
        case a: WT_Var        => typeBindings(a)
        case _: WT_Identifier => t
        case WT_Pair(l, r)    => WT_Pair(sub(l), sub(r))
        case WT_Array(t)      => WT_Array(sub(t))
        case WT_Map(k, v)     => WT_Map(sub(k), sub(v))
        case WT_Object        => WT_Object
        case WT_Optional(t)   => WT_Optional(sub(t))
        case other =>
          throw new TypeException(s"Type ${toString(other)} should not appear in this context",
                                  srcText)
      }
    }
    sub(t)
  }

  def toString(t: WT): String = {
    t match {
      case WT_String         => "String"
      case WT_File           => "File"
      case WT_Boolean        => "Boolean"
      case WT_Int            => "Int"
      case WT_Float          => "Float"
      case WT_Var(i)         => s"Var($i)"
      case WT_Identifier(id) => s"Id(${id})"
      case WT_Pair(l, r)     => s"Pair[${toString(l)}, ${toString(r)}]"
      case WT_Array(t)       => s"Array[${toString(t)}]"
      case WT_Map(k, v)      => s"Map[${toString(k)}, ${toString(v)}]"
      case WT_Object         => "Object"
      case WT_Optional(t)    => s"Optional[${toString(t)}]"

      // a user defined structure
      case WT_Struct(name, members) => s"Struct($name)"

      case WT_Task(name, input, output) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${toString(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${toString(t)}"
          }
          .mkString(", ")
        s"TaskSig($name, input=$inputs, outputs=${outputs})"

      case WT_Workflow(name, input, output) =>
        val inputs = input
          .map {
            case (name, (t, _)) =>
              s"$name -> ${toString(t)}"
          }
          .mkString(", ")
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${toString(t)}"
          }
          .mkString(", ")
        s"WorkflowSig($name, input={$inputs}, outputs={$outputs})"

      // The type of a call to a task or a workflow.
      case WT_Call(name, output: Map[String, WT]) =>
        val outputs = output
          .map {
            case (name, t) =>
              s"$name -> ${toString(t)}"
          }
          .mkString(", ")
        s"Call $name { $outputs }"

      // WT representation for an stdlib function.
      // For example, stdout()
      case WT_Function0(name, output) =>
        s"${name}() -> ${toString(output)}"

      // A function with one argument
      case WT_Function1(name, input, output) =>
        s"${name}(${toString(input)}) -> ${toString(output)}"

      // A function with two arguments. For example:
      // Float size(File, [String])
      case WT_Function2(name, arg1, arg2, output) =>
        s"${name}(${toString(arg1)}, ${toString(arg2)}) -> ${toString(output)}"

      // A function with three arguments. For example:
      // String sub(String, String, String)
      case WT_Function3(name, arg1, arg2, arg3, output) =>
        s"${name}(${toString(arg1)}, ${toString(arg2)}, ${toString(arg3)}) -> ${toString(output)}"
    }
  }
}
