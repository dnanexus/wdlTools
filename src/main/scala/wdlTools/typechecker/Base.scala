package wdlTools.typechecker

// This is the WDL typesystem
object Base {
  sealed trait WdlType

  // There are cases where we don't know the type. For example, an empty array, or an empty map.
  // While evaluating the right hand side we don't know the type.
  //
  // Array[Int] names = []
  // Map[String, File] locations = {}
  case object WdlTypeUnknown extends Type

  // primitive types
  case object WdlTypeString extends WdlType
  case object WdlTypeFile extends WdlType
  case object WdlTypeBoolean extends WdlType
  case object WdlTypeInt extends WdlType
  case object WdlTypeFloat extends WdlType
  case object WdlTypeObject extends WdlType

  // a variable
  case class WdlTypeIdentifier(id: String) extends WdlType

  // compound types
  case class WdlTypePair(l: WdlType, r: WdlType) extends WdlType
  case class WdlTypeArray(t: WdlType, nonEmpty: Boolean = false) extends WdlType
  case class WdlTypeMap(k: WdlType, v: WdlType) extends WdlType
  case class WdlTypeOptional(t: WdlType) extends WdlType

  // a user defined structure
  case class WdlTypeStruct(name: String,
                        members: Map[String, WdlType]) extends WdlType

  // The type of a task.
  //
  // It takes typed-inputs and returns typed-outputs. The boolean flag denotes
  // if input is optional
  case class WdlTypeTask(name: String,
                      input: Map[String, (WdlType, Boolean)],
                      output: Map[String, WdlType]) extends WdlType

  // The type of a workflow.
  // It takes typed-inputs and returns typed-outputs.
  case class WdlTypeWorkflow(name: String,
                          input: Map[String, (WdlType, Boolean)],
                          output: Map[String, WdlType]) extends WdlType

  // The type of a call to a task or a workflow.
  case class WdlTypeCall(name: String,
                      output: Map[String, WdlType]) extends WdlType

  // A standard library function implemented by the engine.
  sealed trait WdlTypeStdlibFunc extends WdlType {
    val name: String
  }

  // WdlType representation for an stdlib function.
  // For example, stdout()
  case class WdlTypeFunctionUnit(name: String, output: WdlType) extends WdlTypeStdlibFunc

  // A function with one argument
  // For example: read_int(FILE_NAME)
  case class WdlTypeFunction1Arg(name: String, input: WdlType, output: WdlType) extends WdlTypeStdlibFunc

  // A function with two arguments. For example:
  // Float size(File, [String])
  case class WdlTypeFunction2Arg(name: String, arg1: WdlType, arg2: WdlType, output: WdlType)
      extends WdlTypeStdlibFunc

  // A function with three arguments. For example:
  // String sub(String, String, String)
  case class WdlTypeFunction3Arg(name: String, arg1: WdlType, arg2: WdlType, arg3: WdlType, output: WdlType)
      extends WdlTypeStdlibFunc

  // A function that obays parametric polymorphism. For example:
  //
  // Array[Array[X]] transpose(Array[Array[X]])
  //
  case class WdlTypeFunctionParamPoly1Arg(name: String, io: WdlType => (WdlType, WdlType))
      extends WdlTypeStdlibFunc

  // A function that obays parametric polymorphism with two inputs.
  //
  // Array[Pair[X,Y]] zip(Array[X], Array[Y])
  case class WdlTypeFunctionParamPoly2Arg(name: String, io: (WdlType, WdlType) => ((WdlType, WdlType), WdlType))
      extends WdlTypeStdlibFunc

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
  def isCoercibleTo(left: WdlType, right: WdlType): Boolean = {
    (left, right) match {
      case (WdlTypeString, WdlTypeString | WdlTypeFile | WdlTypeBoolean | WdlTypeInt | WdlTypeFloat) => true
      case (WdlTypeFile, WdlTypeString | WdlTypeFile)                                       => true
      case (WdlTypeBoolean, WdlTypeBoolean)                                              => true
      case (WdlTypeInt, WdlTypeInt)                                                      => true
      case (WdlTypeFloat, WdlTypeInt | WdlTypeFloat)                                        => true

      case (WdlTypeOptional(l), WdlTypeOptional(r)) => isCoercibleTo(l, r)
      case (WdlTypeOptional(l), r)               => isCoercibleTo(l, r)

      case (WdlTypeArray(l, _), WdlTypeArray(r, _))   => isCoercibleTo(l, r)
      case (WdlTypeMap(kl, vl), WdlTypeMap(kr, vr))   => isCoercibleTo(kl, kr) && isCoercibleTo(vl, vr)
      case (WdlTypePair(l1, l2), WdlTypePair(r1, r2)) => isCoercibleTo(l1, r1) && isCoercibleTo(l2, r2)

      case (WdlTypeIdentifier(structNameL), WdlTypeIdentifier(structNameR)) =>
        structNameL == structNameR

      case (WdlTypeObject, WdlTypeObject) => true

      case (_, WdlTypeUnknown) => true
      case (WdlTypeUnknown, _) => true

      case _ => false
    }
  }
}
