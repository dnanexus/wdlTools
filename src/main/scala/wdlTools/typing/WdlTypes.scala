package wdlTools.typing

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
    val name: String
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
  case class WT_FunctionParamPoly1Arg(name: String,
                                      input: WT => WT,
                                      output: WT => WT) extends WT_StdlibFunc

  // A function that obays parametric polymorphism with two inputs.
  //
  // Array[Pair[X,Y]] zip(Array[X], Array[Y])
  case class WT_FunctionParamPoly2Arg(name: String,
                                      input: (WT, WT) => (WT, WT),
                                      output: (WT, WT) => WT)  extends WT_StdlibFunc


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
  private def isPrimitive(t : WT) : Boolean = {
    t match {
      case WT_String | WT_File | WT_Boolean | WT_Int | WT_Float => true
      case _ => false
    }
  }

  def isCoercibleTo(left: WT, right: WT): Boolean = {
    (left, right) match {
      case (WT_String, x) if isPrimitive(x) => true
        // A null value is converted to the string "null"
      case (WT_String, WT_Optional(x)) if isPrimitive(x) => true
      case (WT_File, WT_String | WT_File)                                    => true
      case (WT_Boolean, WT_Boolean)                                          => true
      case (WT_Int, WT_Int)                                                  => true
      case (WT_Float, WT_Int | WT_Float)                                     => true

      case (WT_Optional(l), WT_Optional(r)) => isCoercibleTo(l, r)

        // An Int is coercible to Int?
      case (WT_Optional(l), r)              => isCoercibleTo(l, r)

      case (WT_Array(l), WT_Array(r))         => isCoercibleTo(l, r)
      case (WT_Map(kl, vl), WT_Map(kr, vr))   => isCoercibleTo(kl, kr) && isCoercibleTo(vl, vr)
      case (WT_Pair(l1, l2), WT_Pair(r1, r2)) => isCoercibleTo(l1, r1) && isCoercibleTo(l2, r2)

      case (WT_Identifier(structNameL), WT_Identifier(structNameR)) =>
        structNameL == structNameR

      case (WT_Object, WT_Object) => true

      case (_, WT_Unknown) => true
      case (WT_Unknown, _) => true

      case _ => false
    }
  }

  // when calling a polymorphic function things get complicated.
  // For example:
  //    select_first([null, 6])
  // The signature for select first is:
  //    Array[X?] -> X
  // we need to figure out that X is Int.
  def unify(a : WT, b : WT) : WT = {
    (a, b) match {
      case (WT_String, WT_String) => WT_String
      case (WT_File, WT_File) => WT_File
      case (WT_Boolean, WT_Boolean) => WT_Boolean
      case (WT_Int, WT_Int) => WT_Int
      case (WT_Float, WT_Float) => WT_Float
      case (WT_Optional(l), WT_Optional(r)) => unify(l, r)
      case (WT_Optional(l), r)              => unify(l, r)
      case (WT_Array(l), WT_Array(r))         => unify(l, r)
      case (WT_Identifier(l), WT_Identifier(r)) if l == r => WT_Identifier(l)
      case (WT_Unknown, x) => x
      case (x, WT_Unknown) => x
      case _ => throw new Exception("cannot unify types")
    }
  }


  def toString(t : WT) : String = {
    t match {
      case WT_Unknown => "X"
      case WT_String => "String"
      case WT_File => "File"
      case WT_Boolean => "Boolean"
      case WT_Int => "Int"
      case WT_Float => "Float"
      case WT_Identifier(id) => s"Identifier(${id})"
      case WT_Pair(l, r) => s"Pair[${toString(l)}, ${toString(r)}]"
      case WT_Array(t) => s"Array[${toString(t)}]"
      case WT_Map(k, v) => s"Map[${toString(k)}, ${toString(v)}]"
      case WT_Object => "Object"
      case WT_Optional(t) => s"Optional[${toString(t)}]"

        // a user defined structure
      case WT_Struct(name, members) => s"Struct($name)"

      case WT_Task(name, input, output) =>
        val inputs = input.map{ case (name, (t, _)) =>
          s"$name -> ${toString(t)}"
        }.mkString(", ")
        val outputs = output.map{ case (name, t) =>
          s"$name -> ${toString(t)}"
        }.mkString(", ")
        s"TaskSig($name, input=$inputs, outputs=${outputs})"

      case WT_Workflow(name, input, output) =>
        val inputs = input.map{ case (name, (t, _)) =>
          s"$name -> ${toString(t)}"
        }.mkString(", ")
        val outputs = output.map{ case (name, t) =>
          s"$name -> ${toString(t)}"
        }.mkString(", ")
        s"WorkflowSig($name, input={$inputs}, outputs={$outputs})"

      // The type of a call to a task or a workflow.
      case WT_Call(name, output: Map[String, WT]) =>
        val outputs = output.map{ case (name, t) =>
          s"$name -> ${toString(t)}"
        }.mkString(", ")
        s"Call $name { $outputs }"

        // WT representation for an stdlib function.
        // For example, stdout()
      case WT_FunctionUnit(name, output) =>
        s"stdlib ${name}() -> ${toString(output)}"

      // A function with one argument
      case WT_Function1Arg(name, input, output) =>
        s"stdlib_${name}(${toString(input)}) -> ${toString(output)}"

        // A function with two arguments. For example:
        // Float size(File, [String])
      case WT_Function2Arg(name, arg1, arg2, output) =>
        s"stdlib_${name}(${toString(arg1)}, ${toString(arg2)}) -> ${toString(output)}"

        // A function with three arguments. For example:
        // String sub(String, String, String)
      case WT_Function3Arg(name, arg1, arg2, arg3, output) =>
        s"stdlib_${name}(${toString(arg1)}, ${toString(arg2)}, ${toString(arg3)}) -> ${toString(output)}"

        // A function that obays parametric polymorphism. For example:
        //
        // Array[Array[X]] transpose(Array[Array[X]])
        //
      case WT_FunctionParamPoly1Arg(name, input, output) =>
        val in = input(WT_Unknown)
        val out = output(WT_Unknown)
        s"stdlib ${name} (${toString(in)}) -> ${toString(out)}"

        // A function that obays parametric polymorphism with two inputs.
        //
        // Array[Pair[X,Y]] zip(Array[X], Array[Y])
      case  WT_FunctionParamPoly2Arg(name: String, input, output) =>
        s"stdlib_Poly2Arg ${name}"
    }
  }
}
