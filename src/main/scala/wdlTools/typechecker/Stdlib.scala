package wdlTools.typechecker

import wdlTools.syntax.AbstractSyntax._
import Base._
import wdlTools.util.Options

case class Stdlib(conf: Options) {

  private val stdlibV1_0: Vector[TypeStdlibFunc] = Vector(
      TypeFunctionUnit("stdout", TypeFile),
      TypeFunctionUnit("stderr", TypeFile),
      TypeFunction1Arg("read_lines", TypeString, TypeArray(TypeString)),
      TypeFunction1Arg("read_lines", TypeFile, TypeArray(TypeString)),
      TypeFunction1Arg("read_tsv", TypeString, TypeArray(TypeArray(TypeString))),
      TypeFunction1Arg("read_tsv", TypeFile, TypeArray(TypeArray(TypeString))),
      TypeFunction1Arg("read_map", TypeString, TypeArray(TypeArray(TypeString))),
      TypeFunction1Arg("read_map", TypeFile, TypeArray(TypeArray(TypeString))),
      TypeFunction1Arg("read_object", TypeString, TypeObject),
      TypeFunction1Arg("read_object", TypeFile, TypeObject),
      TypeFunction1Arg("read_objects", TypeString, TypeArray(TypeObject)),
      TypeFunction1Arg("read_objects", TypeFile, TypeArray(TypeObject)),
      TypeFunction1Arg("read_json", TypeString, TypeUnknown),
      TypeFunction1Arg("read_json", TypeFile, TypeUnknown),
      TypeFunction1Arg("read_int", TypeString, TypeInt),
      TypeFunction1Arg("read_int", TypeFile, TypeInt),
      TypeFunction1Arg("read_string", TypeString, TypeString),
      TypeFunction1Arg("read_string", TypeFile, TypeString),
      TypeFunction1Arg("read_float", TypeString, TypeFloat),
      TypeFunction1Arg("read_float", TypeFile, TypeFloat),
      TypeFunction1Arg("read_boolean", TypeString, TypeBoolean),
      TypeFunction1Arg("read_boolean", TypeFile, TypeBoolean),
      TypeFunction1Arg("write_lines", TypeArray(TypeString), TypeFile),
      TypeFunction1Arg("write_tsv", TypeArray(TypeArray(TypeString)), TypeFile),
      TypeFunction1Arg("write_map", TypeMap(TypeString, TypeString), TypeFile),
      TypeFunction1Arg("write_object", TypeObject, TypeFile),
      TypeFunction1Arg("write_objects", TypeFile, TypeArray(TypeFile)),
      TypeFunction1Arg("write_json", TypeUnknown, TypeFile),
      // Size can take several kinds of arguments.
      TypeFunction1Arg("size", TypeFile, TypeFloat),
      TypeFunction1Arg("size", TypeOptional(TypeFile), TypeFloat),
      TypeFunction1Arg("size", TypeArray(TypeFile), TypeFloat),
      TypeFunction1Arg("size", TypeArray(TypeOptional(TypeFile)), TypeFloat),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      TypeFunction2Arg("size", TypeFile, TypeString, TypeFloat),
      TypeFunction2Arg("size", TypeOptional(TypeFile), TypeString, TypeFloat),
      TypeFunction2Arg("size", TypeArray(TypeFile), TypeString, TypeFloat),
      TypeFunction2Arg("size", TypeArray(TypeOptional(TypeFile)), TypeString, TypeFloat),
      TypeFunction3Arg("sub", TypeString, TypeString, TypeString, TypeString),
      TypeFunction1Arg("range", TypeInt, TypeArray(TypeInt)),
      // functions that obay parametric polymorphism
      TypeFunctionParamPoly1Arg("transpose",
                                (x: Type) => (TypeArray(TypeArray(x)), TypeArray(TypeArray(x)))),
      TypeFunctionParamPoly2Arg(
          "zip",
          (x: Type, y: Type) => ((TypeArray(x), TypeArray(y)), TypeArray(TypePair(x, y)))
      ),
      TypeFunctionParamPoly2Arg(
          "cross",
          (x: Type, y: Type) => ((TypeArray(x), TypeArray(y)), TypeArray(TypePair(x, y)))
      ),
      TypeFunctionParamPoly1Arg("length", (x: Type) => (TypeArray(x), TypeInt)),
      TypeFunctionParamPoly1Arg("flatten", (x: Type) => (TypeArray(TypeArray(x)), TypeArray(x))),
      // Shortcut, can we use an unknown here?
      TypeFunction2Arg("prefix", TypeString, TypeArray(TypeUnknown), TypeString),
      TypeFunctionParamPoly1Arg("select_first", (x: Type) => (TypeArray(x), x)),
      TypeFunctionParamPoly1Arg("defined", (x: Type) => (TypeOptional(x), TypeBoolean)),
      // simple functions again
      TypeFunction1Arg("basename", TypeString, TypeString),
      TypeFunction1Arg("floor", TypeFloat, TypeInt),
      TypeFunction1Arg("ceil", TypeFloat, TypeInt),
      TypeFunction1Arg("round", TypeFloat, TypeInt)
  )

  // build a mapping from a function name to all of its prototypes.
  // Some functions are overloaded, so they may have several.
  private val funcProtoMap: Map[String, Vector[TypeStdlibFunc]] = {
    stdlibV1_0.foldLeft(Map.empty[String, Vector[TypeStdlibFunc]]) {
      case (accu, funcDesc: TypeStdlibFunc) =>
        accu.get(funcDesc.name) match {
          case None =>
            accu + (funcDesc.name -> Vector(funcDesc))
          case Some(protoVec: Vector[TypeStdlibFunc]) =>
            accu + (funcDesc.name -> (protoVec :+ funcDesc))
        }
    }
  }

  // evaluate the output type of a function. This may require calculation because
  // some functions are polymorphic in their inputs.
  private def evalOnePrototype(funcDesc: TypeStdlibFunc, inputArgs: Vector[Type]): Option[Type] = {
    funcDesc match {
      case TypeFunctionUnit(_, tOutput) if inputArgs.isEmpty =>
        Some(tOutput)
      case TypeFunction1Arg(_, arg1, tOutput) if inputArgs == Vector(arg1) =>
        Some(tOutput)
      case TypeFunction2Arg(_, arg1, arg2, tOutput) if inputArgs == Vector(arg1, arg2) =>
        Some(tOutput)
      case TypeFunction3Arg(_, arg1, arg2, arg3, tOutput)
          if inputArgs == Vector(arg1, arg2, arg3) =>
        Some(tOutput)
      case TypeFunctionParamPoly1Arg(_, io) if inputArgs.size == 1 =>
        val typeParam = inputArgs.head
        val (funcIn, funcOut) = io(typeParam)
        if (funcIn == inputArgs.head) Some(funcOut)
        else None
      case TypeFunctionParamPoly2Arg(_, io) if inputArgs.size == 2 =>
        val (param1, param2) = (inputArgs(0), inputArgs(1))
        val (funcIn, funcOut) = io(param1, param2)
        if (funcIn == (inputArgs(0), inputArgs(1))) Some(funcOut)
        else None
      case _ =>
        None
    }
  }

  def apply(funcName: String, inputArgs: Vector[Type]): Type = {
    val candidates = funcProtoMap.get(funcName) match {
      case None =>
        throw new Exception(s"No function named ${funcName} in the standard library")
      case Some(protoVec) =>
        protoVec
    }

    // The function may be overloaded, taking several types of inputs. Try to
    // match all of them against the input.
    val allCandidatePrototypes: Vector[Option[Type]] = candidates.map {
      evalOnePrototype(_, inputArgs)
    }
    val result: Vector[Type] = allCandidatePrototypes.flatten
    result.size match {
      case 0 =>
        throw new Exception(s"Call to ${funcName} is badly typed")
      case 1 =>
        result.head
      case _ =>
        throw new Exception(s"Sanity, call to ${funcName} matches at least two prototypes")
    }
  }
}
