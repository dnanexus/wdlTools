package wdlTools.types

// This is the WDL typesystem
object WdlTypes {
  sealed trait T

  // represents an error that occured during type inference
  sealed trait Invalid extends T {
    val reason: String
    val originalType: Option[T]
  }

  case class T_Invalid(reason: String, originalType: Option[T] = None) extends Invalid

  // primitive types
  case object T_Boolean extends T
  case object T_Int extends T
  case object T_Float extends T
  case object T_String extends T
  case object T_File extends T
  case object T_Directory extends T

  // There are cases where we don't know the type. For example, an empty array, or an empty map.
  // While evaluating the right hand side we don't know the type.
  //
  // Array[Int] names = []
  // Map[String, File] locations = {}
  //
  case object T_Any extends T

  // Polymorphic functions are another place where type variables appear
  case class T_Var(i: Int) extends T

  // a user defined struct name
  case class T_Identifier(id: String) extends T

  // compound types
  case class T_Pair(l: T, r: T) extends T
  case class T_Array(t: T, nonEmpty: Boolean = false) extends T
  case class T_Map(k: T, v: T) extends T
  case object T_Object extends T
  case class T_Optional(t: T) extends T

  // a user defined structure
  sealed trait T_Struct extends T {
    val name: String
    val members: Map[String, T]
  }

  case class T_StructDef(name: String, members: Map[String, T]) extends T_Struct

  case class T_StructInvalid(reason: String, originalStructType: T_Struct)
      extends T_Struct
      with Invalid {
    override val name: String = originalStructType.name
    override val members: Map[String, T] = originalStructType.members
    override val originalType: Option[T] = Some(originalStructType)
  }

  // Anything that can be called. Tasks and workflows implement this trait.
  // The inputs are decorated by whether they are optional.
  sealed trait T_Callable extends T {
    val name: String
    val input: Map[String, (T, Boolean)]
    val output: Map[String, T]
  }

  // Represents a non-existant callable in a Call node
  case class T_CallableInvalid(name: String,
                               reason: String,
                               originalType: Option[T],
                               input: Map[String, (T, Boolean)] = Map.empty,
                               output: Map[String, T] = Map.empty)
      extends T_Callable
      with Invalid

  // The type of a task.
  //
  // It takes typed-inputs and returns typed-outputs. The boolean flag denotes
  // if input is optional
  sealed trait T_Task extends T_Callable

  case class T_TaskDef(name: String, input: Map[String, (T, Boolean)], output: Map[String, T])
      extends T_Task

  case class T_TaskInvalid(reason: String, originalTaskType: T_Task) extends T_Task with Invalid {
    override val name: String = originalTaskType.name
    override val originalType: Option[T] = Some(originalTaskType)
    override val input: Map[String, (T, Boolean)] = originalTaskType.input
    override val output: Map[String, T] = originalTaskType.output
  }

  // The type of a workflow.
  // It takes typed-inputs and returns typed-outputs.
  sealed trait T_Workflow extends T_Callable

  case class T_WorkflowDef(name: String, input: Map[String, (T, Boolean)], output: Map[String, T])
      extends T_Workflow

  case class T_WorkflowInvalid(reason: String, originalWorkflowType: T_Workflow)
      extends T_Workflow
      with Invalid {
    override val name: String = originalWorkflowType.name
    override val originalType: Option[T] = Some(originalWorkflowType)
    override val input: Map[String, (T, Boolean)] = originalWorkflowType.input
    override val output: Map[String, T] = originalWorkflowType.output
  }

  // Result from calling a task or a workflow.
  sealed trait T_Call extends T {
    val name: String
    val output: Map[String, T]
  }

  case class T_CallDef(name: String, output: Map[String, T]) extends T_Call

  case class T_CallInvalid(name: String,
                           reason: String,
                           originalType: Option[T],
                           output: Map[String, T] = Map.empty)
      extends T_Call
      with Invalid

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

  sealed trait T_Document extends T {
    val name: String
  }

  case class T_DocumentDef(name: String) extends T_Document

  case class T_DocumentInvalid(reason: String, originalDocumentType: T_Document)
      extends T_Document
      with Invalid {
    override val name: String = originalDocumentType.name
    override val originalType: Option[T] = Some(originalDocumentType)
  }
}
