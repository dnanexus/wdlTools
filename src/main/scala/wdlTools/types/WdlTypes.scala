package wdlTools.types

import scala.collection.immutable.SeqMap

// This is the WDL typesystem
object WdlTypes {
  sealed trait T

  // primitive types
  case object T_Boolean extends T
  sealed trait T_Numeric extends T
  case object T_Int extends T_Numeric
  case object T_Float extends T_Numeric
  case object T_String extends T
  sealed trait T_Path extends T
  case object T_File extends T_Path
  case object T_Directory extends T_Path

  // There are cases where we don't know the type. For example, an empty array, or an empty map.
  // While evaluating the right hand side we don't know the type.
  //
  // Array[Int] names = []
  // Map[String, File] locations = {}
  //
  case object T_Any extends T

  /**
    * A polymorphic function can accept multiple parameter types and there is covariance of multiple
    * parameters.
    *
    * @param index the type variable index - all variables with the same index must be coercible to the same type
    * @param bounds an optional set of allowed types
    * @example
    * Take the add operator for example:
    *    1 + 1 = 2
    *    1.0 + 1.0 = 2.0
    *    "a" + "b" = "ab"
    *
    * The type signature would be:
    * val t0 = T_Var(0, Set(T_Int, T_Float, T_String))
    * T_Function2("+", t0, t0, t0)
    */
  case class T_Var(index: Int, bounds: Set[T] = Set.empty) extends T

  // a user defined struct name
  case class T_Identifier(id: String) extends T

  // compound types
  case class T_Pair(l: T, r: T) extends T
  case class T_Array(t: T, nonEmpty: Boolean = false) extends T
  case class T_Map(k: T, v: T) extends T
  case object T_Object extends T
  case class T_Optional(t: T) extends T

  // a user defined structure
  case class T_Struct(name: String, members: SeqMap[String, T]) extends T

  // Anything that can be called. Tasks and workflows implement this trait.
  // The inputs are decorated by whether they are optional.
  sealed trait T_Callable extends T {
    val name: String
    val input: SeqMap[String, (T, Boolean)]
    val output: SeqMap[String, T]
  }

  // The type of a task.
  //
  // It takes typed-inputs and returns typed-outputs. The boolean flag denotes
  // if input is optional
  case class T_Task(name: String, input: SeqMap[String, (T, Boolean)], output: SeqMap[String, T])
      extends T_Callable

  // The type of a workflow.
  // It takes typed-inputs and returns typed-outputs.
  case class T_Workflow(name: String,
                        input: SeqMap[String, (T, Boolean)],
                        output: SeqMap[String, T])
      extends T_Callable

  // Result from calling a task or a workflow.
  case class T_Call(name: String, output: SeqMap[String, T]) extends T

  // A standard library function implemented by the engine.
  sealed trait T_Function extends T {
    val name: String
    val output: T
  }

  // T representation for an stdlib function.
  // For example, stdout()
  case class T_Function0(name: String, output: T) extends T_Function

  // A function with one argument
  // Example:
  //   read_int(FILE_NAME)
  //   Array[Array[X]] transpose(Array[Array[X]])
  case class T_Function1(name: String, input: T, output: T) extends T_Function

  // A function with two arguments. For example:
  //   Float size(File, [String])
  //   Array[Pair(X,Y)] zip(Array[X], Array[Y])
  case class T_Function2(name: String, arg1: T, arg2: T, output: T) extends T_Function

  // A function with three arguments. For example:
  // String sub(String, String, String)
  case class T_Function3(name: String, arg1: T, arg2: T, arg3: T, output: T) extends T_Function
}
