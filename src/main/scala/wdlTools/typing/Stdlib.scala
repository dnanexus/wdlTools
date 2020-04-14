package wdlTools.typing

import WdlTypes._
import wdlTools.util.Options
import wdlTools.util.Verbosity._
import wdlTools.syntax.AbstractSyntax
import Util._

case class Stdlib(conf: Options) {

  private val stdlibV1_0: Vector[WT_StdlibFunc] = Vector(
      WT_FunctionUnit("stdout", WT_File),
      WT_FunctionUnit("stderr", WT_File),
      WT_Function1Arg("read_lines", WT_File, WT_Array(WT_String)),
      WT_Function1Arg("read_tsv", WT_File, WT_Array(WT_Array(WT_String))),
      WT_Function1Arg("read_map", WT_File, WT_Array(WT_Array(WT_String))),
      WT_Function1Arg("read_object", WT_File, WT_Object),
      WT_Function1Arg("read_objects", WT_File, WT_Array(WT_Object)),
      WT_Function1Arg("read_json", WT_File, WT_Unknown),
      WT_Function1Arg("read_int", WT_File, WT_Int),
      WT_Function1Arg("read_string", WT_File, WT_String),
      WT_Function1Arg("read_float", WT_File, WT_Float),
      WT_Function1Arg("read_boolean", WT_File, WT_Boolean),
      WT_Function1Arg("write_lines", WT_Array(WT_String), WT_File),
      WT_Function1Arg("write_tsv", WT_Array(WT_Array(WT_String)), WT_File),
      WT_Function1Arg("write_map", WT_Map(WT_String, WT_String), WT_File),
      WT_Function1Arg("write_object", WT_Object, WT_File),
      WT_Function1Arg("write_objects", WT_File, WT_Array(WT_File)),
      WT_Function1Arg("write_json", WT_Unknown, WT_File),

    // Size can take several kinds of arguments.
      WT_Function1Arg("size", WT_File, WT_Float),
      WT_Function1Arg("size", WT_Optional(WT_File), WT_Float),
      WT_Function1Arg("size", WT_Array(WT_File), WT_Float),
      WT_Function1Arg("size", WT_Array(WT_Optional(WT_File)), WT_Float),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      WT_Function2Arg("size", WT_File, WT_String, WT_Float),
      WT_Function2Arg("size", WT_Optional(WT_File), WT_String, WT_Float),
      WT_Function2Arg("size", WT_Array(WT_File), WT_String, WT_Float),
      WT_Function2Arg("size", WT_Array(WT_Optional(WT_File)), WT_String, WT_Float),

    WT_Function3Arg("sub", WT_String, WT_String, WT_String, WT_String),
      WT_Function1Arg("range", WT_Int, WT_Array(WT_Int)),

    // Array[Array[X]] transpose(Array[Array[X]])
      WT_Function1Arg("transpose",
                      WT_Array(WT_Array(WT_Var(0))),
                      WT_Array(WT_Array(WT_Var(0)))),

    // Array[Pair(X,Y)] zip(Array[X], Array[Y])
    WT_Function2Arg("zip",
                    (WT_Array(WT_Var(0)), WT_Array(WT_Var(1))),
                    WT_Array(WT_Pair(WT_Var(0), WT_Var(1)))),

    // Array[Pair(X,Y)] cross(Array[X], Array[Y])
    WT_Function2Arg("cross",
                    (WT_Array(WT_Var(0)), WT_Array(WT_Var(1))),
                    WT_Array(WT_Pair(WT_Var(0), WT_Var(1)))),

    // Integer length(Array[X])
    WT_Function1Arg("length", WT_Array(WT_Var(0)), WT_Int)

    // Array[X] flatten(Array[Array[X]])
    WT_Function1Arg("flatten",
                    WT_Array(WT_Array(WT_Var(0))),
                    WT_Array(WT_Var(0))),

    WT_Function2Arg("prefix", WT_String, WT_Array(WT_Var(0)), WT_String),

    WT_Function1Arg("select_first",
                    WT_Array(WT_Optional(WT_Var(0))),
                    WT_Var(0))
      WT_Function1Arg("defined",
                      WT_Optional(WT_Var(0)),
                      WT_Boolean),

      // simple functions again
      WT_Function1Arg("basename", WT_String, WT_String),
      WT_Function1Arg("floor", WT_Float, WT_Int),
      WT_Function1Arg("ceil", WT_Float, WT_Int),
      WT_Function1Arg("round", WT_Float, WT_Int),
      // extras not mentioned in the specification
      WT_Function1Arg("glob", WT_String, WT_Array(WT_File))
  )

  // build a mapping from a function name to all of its prototypes.
  // Some functions are overloaded, so they may have several.
  private val funcProtoMap: Map[String, Vector[WT_StdlibFunc]] = {
    stdlibV1_0.foldLeft(Map.empty[String, Vector[WT_StdlibFunc]]) {
      case (accu, funcDesc: WT_StdlibFunc) =>
        accu.get(funcDesc.name) match {
          case None =>
            accu + (funcDesc.name -> Vector(funcDesc))
          case Some(protoVec: Vector[WT_StdlibFunc]) =>
            accu + (funcDesc.name -> (protoVec :+ funcDesc))
        }
    }
  }

  // check if the inputs match the function signature
  private def inputTypesMatchSig(inputTypes : Vector[WT],
                                 funcInputSig : Vector[WT],
                                 funcOutputSig : Vector[WT]) : Some(Vector[WT]) = {
    if (inputTypes.size != funcInputSig.size) {
      // function is called with the wrong number of arguments
      return None
    }

    val pairs = inputTypes zip funcInputSig

    // we have type variables.
    val solution =
      try {
        Some(unify(typePairs))
      } catch {
        _ : TypeUnificationException =>
        None
      }

    solution match {
      case None => None
      case
    // Now that we know the type variables, we can substitute
    substitution(solution, funcOutputSig)

    // find all the type variables
    val typeBindings = (inputTypes ++ funcInputSig).foldLeft(Set.empty[WT_Var]) {
      case (accu, WT_Var(i)) =>
        accu + WT_Var(i)
      case (accu, _) =>
        accu
    }
    if (typeBindings.size == 0) {
      // no type unification needed. Check
      // that all inputs can be coerced to the function inputs.
      val inputsMatch = pairs.forAll{
        case (fInput, aInput) => Utils.isCoercibleTo(fInput, aInput)
      }
      if (inputsMatch)
        Some(funcOutputSig)
      else
        None
    }

  }

  // evaluate the output type of a function. This may require calculation because
  // some functions are polymorphic in their inputs.
  private def evalOnePrototype(funcDesc: WT_StdlibFunc, inputTypes: Vector[WT]): Option[WT] = {
    funcDesc match {
      case WT_Function0(_, tOutput) if inputTypes.isEmpty =>
        Some(tOutput)
      case WT_Function1(_, arg1, tOutput) =>
        unify(Vector(arg1), funcDesc.input, funcDesc.output)
      case WT_Function2(_, arg1, arg2, tOutput) =>
        unify(Vector(arg1), funcDesc.input)
      case WT_Function3(_, arg1, arg2, arg3, tOutput) =>
        unify(Vector(arg1, arg2, arg3), funcDesc.input)
      case _ =>
        None
    }
          inputTypesMatchSig(inputTypes, Vector(arg1))  }

  def apply(funcName: String, inputTypes: Vector[WT], expr: AbstractSyntax.Expr): WT = {
    val candidates = funcProtoMap.get(funcName) match {
      case None =>
        throw new TypeException(s"No function named ${funcName} in the standard library", expr.text)
      case Some(protoVec) =>
        protoVec
    }

    // The function may be overloaded, taking several types of inputs. Try to
    // match all of them against the input.
    val allCandidatePrototypes: Vector[Option[WT]] = candidates.map {
      evalOnePrototype(_, inputTypes)
    }
    val result: Vector[WT] = allCandidatePrototypes.flatten
    result.size match {
      case 0 =>
        val inputsStr = inputTypes.map(Util.toString(_)).mkString("\n")
        val candidatesStr = candidates.map(Util.toString(_)).mkString("\n")
        throw new TypeException(s"""|Invoking stdlib function ${funcName} with badly typed arguments
                                    |${candidatesStr}
                                    |inputs: ${inputsStr}
                                    |""".stripMargin,
                                expr.text)
      case 1 =>
        result.head
      case n =>
        // Match more than one prototype. If they all have the same output type, then it doesn't matter
        // though.
        val possibleOutputTypes : Set[WT] = result.toSet
        if (possibleOutputTypes.size > 1)
          throw new TypeException(s"""|Call to ${funcName} matches ${n} prototypes with different
                                      |output types (${possibleOutputTypes})"""
                                    .stripMargin.replaceAll("\n", " "),
                                  expr.text)
        possibleOutputTypes.toVector.head
    }
  }
}
