package wdlTools.typing

import WdlTypes._
import wdlTools.util.Options
import wdlTools.util.Verbosity._
import wdlTools.syntax.AbstractSyntax

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
      // functions that obay parametric polymorphism
      WT_FunctionParamPoly1Arg("transpose",
                               x => WT_Array(WT_Array(x)),
                               x => WT_Array(WT_Array(x))),
      WT_FunctionParamPoly2Arg("zip",
                               (x, y) => (WT_Array(x), WT_Array(y)),
                               (x, y) =>  WT_Array(WT_Pair(x, y))),
      WT_FunctionParamPoly2Arg("cross",
                               (x, y) => (WT_Array(x), WT_Array(y)),
                               (x, y) => WT_Array(WT_Pair(x, y))),
      WT_FunctionParamPoly1Arg("length",
                               x => WT_Array(x),
                               x => WT_Int),
      WT_FunctionParamPoly1Arg("flatten",
                               x => WT_Array(WT_Array(x)),
                               x => WT_Array(x)),
      // Shortcut, can we use an unknown here?
      WT_Function2Arg("prefix", WT_String, WT_Array(WT_Unknown), WT_String),
      WT_FunctionParamPoly1Arg("select_first",
                               x => WT_Array(WT_Optional(x)),
                               x => x),
      WT_FunctionParamPoly1Arg("defined",
                               x => WT_Optional(x),
                               x => WT_Boolean),
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
                                 functionInputSig : Vector[WT]) : Boolean = {
    if (inputTypes.size != functionInputSig.size) {
      return false
    }
    val pairs = inputTypes zip functionInputSig

    pairs.forall {
      case (inType, sigT) => isCoercibleTo(sigT, inType)
    }
  }

  // evaluate the output type of a function. This may require calculation because
  // some functions are polymorphic in their inputs.
  private def evalOnePrototype(funcDesc: WT_StdlibFunc, inputTypes: Vector[WT]): Option[WT] = {
    funcDesc match {
      case WT_FunctionUnit(_, tOutput) if inputTypes.isEmpty =>
        Some(tOutput)
      case WT_Function1Arg(_, arg1, tOutput) if inputTypesMatchSig(inputTypes, Vector(arg1)) =>
        Some(tOutput)
      case WT_Function2Arg(_, arg1, arg2, tOutput) if inputTypesMatchSig(inputTypes, Vector(arg1, arg2)) =>
        Some(tOutput)
      case WT_Function3Arg(_, arg1, arg2, arg3, tOutput) if inputTypesMatchSig(inputTypes, Vector(arg1, arg2, arg3)) =>
        Some(tOutput)
      case WT_FunctionParamPoly1Arg(name, funcIn, funcOut) if inputTypes.size == 1 =>
        val typeParam = inputTypes.head
        val inArgType : WT = funcIn(WT_Unknown)
        val t =
          try {
            Some(unify(typeParam, inArgType))
          } catch {
            case _ : Throwable =>
              None
          }
        if (conf.verbosity == Verbose) {
          System.out.println(s"""|evalOnePrototype $name
                                 | input: $typeParam
                                 | unify: $t
                                 |""".stripMargin)
        }
        t.map(funcOut(_))

      case WT_FunctionParamPoly2Arg(_, funcIn, funcOut) if inputTypes.size == 2 =>
        val (param1, param2) = (inputTypes(0), inputTypes(1))
        val inArgType = funcIn(param1, param2)
        val outArgType = funcOut(param1, param2)
        if (inArgType == (inputTypes(0), inputTypes(1))) Some(outArgType)
        else None
      case _ =>
        None
    }
  }

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
        val inputsStr = inputTypes.map(WdlTypes.toString(_)).mkString("\n")
        val candidatesStr = candidates.map(WdlTypes.toString(_)).mkString("\n")
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
