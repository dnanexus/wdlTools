package wdlTools.typechecker

import wdlTools.syntax.AbstractSyntax._

object Base {
  // There are cases where we don't know the type. For example, an empty array, or an empty map.
  // While evaluating the right hand side we don't know the type.
  //
  // Array[Int] names = []
  // Map[String, File] locations = {}
  case object TypeUnknown extends Type

  // The type of a task.
  //
  // It takes typed-inputs and returns typed-outputs. The boolean flag denotes
  // if input is optional
  case class TypeTask(name: String, input: Map[String, (Type, Boolean)], output: Map[String, Type])
      extends Type

  // The type of a workflow.
  // It takes typed-inputs and returns typed-outputs.
  case class TypeWorkflow(name: String,
                          input: Map[String, (Type, Boolean)],
                          output: Map[String, Type])
      extends Type

  // The type of a call to a task or a workflow.
  case class TypeCall(name: String, output: Map[String, Type]) extends Type

  // A standard library function implemented by the engine.
  sealed trait TypeStdlibFunc extends Type {
    val name: String
  }

  // Type representation for an stdlib function.
  // For example, stdout()
  case class TypeFunctionUnit(name: String, output: Type) extends TypeStdlibFunc

  // A function with one argument
  // For example: read_int(FILE_NAME)
  case class TypeFunction1Arg(name: String, input: Type, output: Type) extends TypeStdlibFunc

  // A function with two arguments. For example:
  // Float size(File, [String])
  case class TypeFunction2Arg(name: String, arg1: Type, arg2: Type, output: Type)
      extends TypeStdlibFunc

  // A function with three arguments. For example:
  // String sub(String, String, String)
  case class TypeFunction3Arg(name: String, arg1: Type, arg2: Type, arg3: Type, output: Type)
      extends TypeStdlibFunc

  // A function that obays parametric polymorphism. For example:
  //
  // Array[Array[X]] transpose(Array[Array[X]])
  //
  case class TypeFunctionParamPoly1Arg(name: String, io: (Type => (Type, Type)))
      extends TypeStdlibFunc

  // A function that obays parametric polymorphism with two inputs.
  //
  // Array[Pair[X,Y]] zip(Array[X], Array[Y])
  case class TypeFunctionParamPoly2Arg(name: String, io: ((Type, Type) => ((Type, Type), Type)))
      extends TypeStdlibFunc

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
  def isCoercibleTo(left: Type, right: Type): Boolean = {
    (left, right) match {
      case (TypeString, (TypeString | TypeFile | TypeBoolean | TypeInt | TypeFloat)) => true
      case (TypeFile, (TypeString | TypeFile))                                       => true
      case (TypeBoolean, TypeBoolean)                                                => true
      case (TypeInt, TypeInt)                                                        => true
      case (TypeFloat, (TypeInt | TypeFloat))                                        => true

      case (TypeOptional(l), TypeOptional(r)) => isCoercibleTo(l, r)
      case (TypeOptional(l), r)               => isCoercibleTo(l, r)

      case (TypeArray(l, _), TypeArray(r, _))   => isCoercibleTo(l, r)
      case (TypeMap(kl, vl), TypeMap(kr, vr))   => isCoercibleTo(kl, kr) && isCoercibleTo(vl, vr)
      case (TypePair(l1, l2), TypePair(r1, r2)) => isCoercibleTo(l1, r1) && isCoercibleTo(l2, r2)

      case (TypeIdentifier(structNameL), TypeIdentifier(structNameR)) =>
        structNameL == structNameR

      case (TypeObject, TypeObject) => true

      case (_, TypeUnknown) => true
      case (TypeUnknown, _) => true

      case _ => false
    }
  }
}
