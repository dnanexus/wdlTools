package wdlTools.typechecker

// This is the WDL typesystem
object WdlTypes {
  sealed trait WT

  // There are cases where we don't know the type. For example, an empty array, or an empty map.
  // While evaluating the right hand side we don't know the type.
  //
  // Array[Int] names = []
  // Map[String, File] locations = {}
  case object WT_Unknown extends WT

  // primitive types
  case object WT_String extends WT
  case object WT_File extends WT
  case object WT_Boolean extends WT
  case object WT_Int extends WT
  case object WT_Float extends WT

  // a variable
  case class WT_Identifier(id: String) extends WT

  // compound types
  case class WT_Pair(l: WT, r: WT) extends WT
  case class WT_Array(t: WT) extends WT
  case class WT_Map(k: WT, v: WT) extends WT
  case object WT_Object extends WT
  case class WT_Optional(t: WT) extends WT

  // a user defined structure
  case class WT_Struct(name: String, members: Map[String, WT]) extends WT

  // Anything that can be called. A general group that includesw tasks
  // and workflows
  //
  sealed trait WT_Callable extends WT {
    val name : String
    val input: Map[String, (WT, Boolean)]
    val output: Map[String, WT]
  }

  // The type of a task.
  //
  // It takes typed-inputs and returns typed-outputs. The boolean flag denotes
  // if input is optional
  case class WT_Task(name: String, input: Map[String, (WT, Boolean)], output: Map[String, WT])
      extends WT_Callable

  // The type of a workflow.
  // It takes typed-inputs and returns typed-outputs.
  case class WT_Workflow(name: String, input: Map[String, (WT, Boolean)], output: Map[String, WT])
      extends WT_Callable

  // The type of a call to a task or a workflow.
  case class WT_Call(name: String, output: Map[String, WT]) extends WT

  // A standard library function implemented by the engine.
  sealed trait WT_StdlibFunc extends WT {
    val name: String
  }

  // WT representation for an stdlib function.
  // For example, stdout()
  case class WT_FunctionUnit(name: String, output: WT) extends WT_StdlibFunc

  // A function with one argument
  // For example: read_int(FILE_NAME)
  case class WT_Function1Arg(name: String, input: WT, output: WT) extends WT_StdlibFunc

  // A function with two arguments. For example:
  // Float size(File, [String])
  case class WT_Function2Arg(name: String, arg1: WT, arg2: WT, output: WT) extends WT_StdlibFunc

  // A function with three arguments. For example:
  // String sub(String, String, String)
  case class WT_Function3Arg(name: String, arg1: WT, arg2: WT, arg3: WT, output: WT)
      extends WT_StdlibFunc

  // A function that obays parametric polymorphism. For example:
  //
  // Array[Array[X]] transpose(Array[Array[X]])
  //
  case class WT_FunctionParamPoly1Arg(name: String, io: WT => (WT, WT)) extends WT_StdlibFunc

  // A function that obays parametric polymorphism with two inputs.
  //
  // Array[Pair[X,Y]] zip(Array[X], Array[Y])
  case class WT_FunctionParamPoly2Arg(name: String, io: (WT, WT) => ((WT, WT), WT))
      extends WT_StdlibFunc
}
