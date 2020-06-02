package wdlTools.types

import WdlTypes._
import wdlTools.syntax.WdlVersion

case class Stdlib(conf: TypeOptions, version: WdlVersion) {
  private val unify = Unification(conf)

  // Some functions are overloaded and can take several kinds of arguments.
  // A particulary problematic one is size.
  // There is WDL code that calls size with File, and File?. So what is the prototype of "size" ?
  //
  // We chould use:
  //       T_File -> T_Float
  // or
  //       T_File? -> T_Float
  // or both:
  //       T_File -> T_Float
  //       T_File? -> T_Float
  //
  // A constraint we wish to maintain is that looking up a function prototype will
  // return just one result. Therefore, we ended up with the prototype:
  //       T_File? -> T_Float
  // because with the current type system T_File? is more general than T_File.
  // This is not pretty.

  private val draft2Prototypes: Vector[T_Function] = Vector(
      T_Function0("stdout", T_File),
      T_Function0("stderr", T_File),
      T_Function1("read_lines", T_File, T_Array(T_String)),
      T_Function1("read_tsv", T_File, T_Array(T_Array(T_String))),
      T_Function1("read_map", T_File, T_Map(T_String, T_String)),
      T_Function1("read_object", T_File, T_Object),
      T_Function1("read_objects", T_File, T_Array(T_Object)),
      T_Function1("read_json", T_File, T_Any),
      T_Function1("read_int", T_File, T_Int),
      T_Function1("read_string", T_File, T_String),
      T_Function1("read_float", T_File, T_Float),
      T_Function1("read_boolean", T_File, T_Boolean),
      T_Function1("write_lines", T_Array(T_String), T_File),
      T_Function1("write_tsv", T_Array(T_Array(T_String)), T_File),
      T_Function1("write_map", T_Map(T_String, T_String), T_File),
      T_Function1("write_object", T_Object, T_File),
      T_Function1("write_objects", T_Array(T_Object), T_File),
      T_Function1("write_json", T_Any, T_File),
      // Size can take several kinds of arguments.
      T_Function1("size", T_Optional(T_File), T_Float),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      T_Function2("size", T_Optional(T_File), T_String, T_Float),
      T_Function3("sub", T_String, T_String, T_String, T_String),
      T_Function1("range", T_Int, T_Array(T_Int)),
      // Array[Array[X]] transpose(Array[Array[X]])
      T_Function1("transpose", T_Array(T_Array(T_Var(0))), T_Array(T_Array(T_Var(0)))),
      // Array[Pair(X,Y)] zip(Array[X], Array[Y])
      T_Function2("zip", T_Array(T_Var(0)), T_Array(T_Var(1)), T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Array[Pair(X,Y)] cross(Array[X], Array[Y])
      T_Function2("cross",
                  T_Array(T_Var(0)),
                  T_Array(T_Var(1)),
                  T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Integer length(Array[X])
      T_Function1("length", T_Array(T_Var(0)), T_Int),
      // Array[X] flatten(Array[Array[X]])
      T_Function1("flatten", T_Array(T_Array(T_Var(0))), T_Array(T_Var(0))),
      T_Function2("prefix", T_String, T_Array(T_Var(0)), T_Array(T_String)),
      T_Function1("select_first", T_Array(T_Optional(T_Var(0))), T_Var(0)),
      T_Function1("select_all", T_Array(T_Optional(T_Var(0))), T_Array(T_Var(0))),
      T_Function1("defined", T_Optional(T_Var(0)), T_Boolean),
      // simple functions again
      // basename has two variants
      T_Function1("basename", T_String, T_String),
      T_Function1("floor", T_Float, T_Int),
      T_Function1("ceil", T_Float, T_Int),
      T_Function1("round", T_Float, T_Int),
      // not mentioned in the specification
      T_Function1("glob", T_String, T_Array(T_File))
  )

  private val v1Prototypes: Vector[T_Function] = Vector(
      T_Function0("stdout", T_File),
      T_Function0("stderr", T_File),
      T_Function1("read_lines", T_File, T_Array(T_String)),
      T_Function1("read_tsv", T_File, T_Array(T_Array(T_String))),
      T_Function1("read_map", T_File, T_Map(T_String, T_String)),
      T_Function1("read_object", T_File, T_Object),
      T_Function1("read_objects", T_File, T_Array(T_Object)),
      T_Function1("read_json", T_File, T_Any),
      T_Function1("read_int", T_File, T_Int),
      T_Function1("read_string", T_File, T_String),
      T_Function1("read_float", T_File, T_Float),
      T_Function1("read_boolean", T_File, T_Boolean),
      T_Function1("write_lines", T_Array(T_String), T_File),
      T_Function1("write_tsv", T_Array(T_Array(T_String)), T_File),
      T_Function1("write_map", T_Map(T_String, T_String), T_File),
      T_Function1("write_object", T_Object, T_File),
      T_Function1("write_objects", T_Array(T_Object), T_File),
      T_Function1("write_json", T_Any, T_File),
      // Size can take several kinds of arguments.
      T_Function1("size", T_Optional(T_File), T_Float),
      T_Function1("size", T_Array(T_File), T_Float),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      T_Function2("size", T_Optional(T_File), T_String, T_Float),
      T_Function2("size", T_Array(T_File), T_String, T_Float),
      T_Function3("sub", T_String, T_String, T_String, T_String),
      T_Function1("range", T_Int, T_Array(T_Int)),
      // Array[Array[X]] transpose(Array[Array[X]])
      T_Function1("transpose", T_Array(T_Array(T_Var(0))), T_Array(T_Array(T_Var(0)))),
      // Array[Pair(X,Y)] zip(Array[X], Array[Y])
      T_Function2("zip", T_Array(T_Var(0)), T_Array(T_Var(1)), T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Array[Pair(X,Y)] cross(Array[X], Array[Y])
      T_Function2("cross",
                  T_Array(T_Var(0)),
                  T_Array(T_Var(1)),
                  T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Integer length(Array[X])
      T_Function1("length", T_Array(T_Var(0)), T_Int),
      // Array[X] flatten(Array[Array[X]])
      T_Function1("flatten", T_Array(T_Array(T_Var(0))), T_Array(T_Var(0))),
      T_Function2("prefix", T_String, T_Array(T_Var(0)), T_Array(T_String)),
      T_Function1("select_first", T_Array(T_Optional(T_Var(0))), T_Var(0)),
      T_Function1("select_all", T_Array(T_Optional(T_Var(0))), T_Array(T_Var(0))),
      T_Function1("defined", T_Optional(T_Var(0)), T_Boolean),
      // simple functions again
      // basename has two variants
      T_Function1("basename", T_String, T_String),
      T_Function2("basename", T_String, T_String, T_String),
      T_Function1("floor", T_Float, T_Int),
      T_Function1("ceil", T_Float, T_Int),
      T_Function1("round", T_Float, T_Int),
      // not mentioned in the specification
      T_Function1("glob", T_String, T_Array(T_File))
  )

  // Add the signatures for draft2 and v2 here
  private val v2Prototypes: Vector[T_Function] = Vector(
      T_Function0("stdout", T_File),
      T_Function0("stderr", T_File),
      T_Function1("read_lines", T_File, T_Array(T_String)),
      T_Function1("read_tsv", T_File, T_Array(T_Array(T_String))),
      T_Function1("read_map", T_File, T_Map(T_String, T_String)),
      T_Function1("read_json", T_File, T_Any),
      T_Function1("read_int", T_File, T_Int),
      T_Function1("read_string", T_File, T_String),
      T_Function1("read_float", T_File, T_Float),
      T_Function1("read_boolean", T_File, T_Boolean),
      T_Function1("write_lines", T_Array(T_String), T_File),
      T_Function1("write_tsv", T_Array(T_Array(T_String)), T_File),
      T_Function1("write_map", T_Map(T_String, T_String), T_File),
      T_Function1("write_json", T_Any, T_File),
      // Size can take several kinds of arguments.
      T_Function1("size", T_Optional(T_File), T_Float),
      T_Function1("size", T_Array(T_File), T_Float),
      // Size takes an optional units parameter (KB, KiB, MB, GiB, ...)
      T_Function2("size", T_Optional(T_File), T_String, T_Float),
      T_Function2("size", T_Array(T_File), T_String, T_Float),
      T_Function3("sub", T_String, T_String, T_String, T_String),
      T_Function1("range", T_Int, T_Array(T_Int)),
      // Array[Array[X]] transpose(Array[Array[X]])
      T_Function1("transpose", T_Array(T_Array(T_Var(0))), T_Array(T_Array(T_Var(0)))),
      // Array[Pair(X,Y)] zip(Array[X], Array[Y])
      T_Function2("zip", T_Array(T_Var(0)), T_Array(T_Var(1)), T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Array[Pair(X,Y)] cross(Array[X], Array[Y])
      T_Function2("cross",
                  T_Array(T_Var(0)),
                  T_Array(T_Var(1)),
                  T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Array[Pair[X,Y]] as_pairs(Map[X,Y])
      T_Function1("as_pairs", T_Map(T_Var(0), T_Var(1)), T_Array(T_Pair(T_Var(0), T_Var(1)))),
      // Map[X,Y] as_map(Array[Pair[X,Y]])
      T_Function1("as_map", T_Array(T_Pair(T_Var(0), T_Var(1))), T_Map(T_Var(0), T_Var(1))),
      // // Array[X] keys(Map[X,Y])
      T_Function1("keys", T_Map(T_Var(0), T_Any), T_Array(T_Var(0))),
      // Map[X,Array[Y]] collect_by_key(Array[Pair[X,Y]])
      T_Function1("collect_by_keys",
                  T_Array(T_Pair(T_Var(0), T_Var(1))),
                  T_Map(T_Var(0), T_Array(T_Var(1)))),
      // Integer length(Array[X])
      T_Function1("length", T_Array(T_Var(0)), T_Int),
      // Array[X] flatten(Array[Array[X]])
      T_Function1("flatten", T_Array(T_Array(T_Var(0))), T_Array(T_Var(0))),
      T_Function2("prefix", T_String, T_Array(T_Var(0)), T_Array(T_String)),
      T_Function2("suffix", T_String, T_Array(T_Var(0)), T_Array(T_String)),
      T_Function1("quote", T_Array(T_Var(0)), T_Array(T_String)),
      T_Function1("squote", T_Array(T_Var(0)), T_Array(T_String)),
      T_Function1("select_first", T_Array(T_Optional(T_Var(0))), T_Var(0)),
      T_Function1("select_all", T_Array(T_Optional(T_Var(0))), T_Array(T_Var(0))),
      T_Function1("defined", T_Optional(T_Var(0)), T_Boolean),
      // simple functions again
      // basename has two variants
      T_Function1("basename", T_String, T_String),
      T_Function2("basename", T_String, T_String, T_String),
      T_Function1("floor", T_Float, T_Int),
      T_Function1("ceil", T_Float, T_Int),
      T_Function1("round", T_Float, T_Int),
      // not mentioned in the specification
      T_Function1("glob", T_String, T_Array(T_File))
  )

  // choose the standard library prototypes according to the WDL version
  private val protoTable: Vector[T_Function] = version match {
    case WdlVersion.Draft_2 => draft2Prototypes
    case WdlVersion.V1      => v1Prototypes
    case WdlVersion.V2      => v2Prototypes
    case other              => throw new RuntimeException(s"Unsupported WDL version ${other}")
  }

  // build a mapping from a function name to all of its prototypes.
  // Some functions are overloaded, so they may have several.
  private val funcProtoMap: Map[String, Vector[T_Function]] = {
    protoTable.foldLeft(Map.empty[String, Vector[T_Function]]) {
      case (accu, funcDesc: T_Function) =>
        accu.get(funcDesc.name) match {
          case None =>
            accu + (funcDesc.name -> Vector(funcDesc))
          case Some(protoVec: Vector[T_Function]) =>
            accu + (funcDesc.name -> (protoVec :+ funcDesc))
        }
    }
  }

  // evaluate the output type of a function. This may require calculation because
  // some functions are polymorphic in their inputs.
  private def evalOnePrototype(funcDesc: T_Function,
                               inputTypes: Vector[T]): Option[(T, T_Function)] = {
    val arity = inputTypes.size
    val args = (arity, funcDesc) match {
      case (0, T_Function0(_, _))                   => Vector.empty
      case (1, T_Function1(_, arg1, _))             => Vector(arg1)
      case (2, T_Function2(_, arg1, arg2, _))       => Vector(arg1, arg2)
      case (3, T_Function3(_, arg1, arg2, arg3, _)) => Vector(arg1, arg2, arg3)
      case (_, _)                                   => return None
    }
    try {
      val (_, ctx) = unify.unifyFunctionArguments(args, inputTypes, Map.empty)
      val t = unify.substitute(funcDesc.output, ctx)
      Some((t, funcDesc))
    } catch {
      case _: TypeUnificationException =>
        None
    }
  }

  def apply(funcName: String, inputTypes: Vector[T]): (T, T_Function) = {
    val candidates = funcProtoMap.get(funcName) match {
      case None =>
        throw new StdlibFunctionException(s"No function named ${funcName} in the standard library")
      case Some(protoVec) =>
        protoVec
    }

    // The function may be overloaded, taking several types of inputs. Try to
    // match all of them against the input.
    val allCandidatePrototypes: Vector[Option[(T, T_Function)]] = candidates.map {
      try {
        evalOnePrototype(_, inputTypes)
      } catch {
        case e: SubstitutionException =>
          throw new StdlibFunctionException(e.getMessage)
      }
    }
    val results: Vector[(T, T_Function)] = allCandidatePrototypes.flatten
    results.size match {
      case 0 =>
        val inputsStr = inputTypes.map(Util.typeToString).mkString("\n")
        val candidatesStr = candidates.map(Util.typeToString(_)).mkString("\n")
        val msg = s"""|Invoking stdlib function ${funcName} with badly typed arguments
                      |${candidatesStr}
                      |inputs: ${inputsStr}
                      |""".stripMargin
        throw new StdlibFunctionException(msg)
      case 1 =>
        results.head
      case n =>
        // Match more than one prototype. If they all have the same output type, then it doesn't matter
        // though.
        val prototypeDescriptions = results
          .map {
            case (_, funcSig) =>
              Util.typeToString(funcSig)
          }
          .mkString("\n")
        val msg = s"""|Call to ${funcName} matches ${n} prototypes
                      |inputTypes: ${inputTypes}
                      |prototypes:
                      |${prototypeDescriptions}
                      |""".stripMargin
        throw new StdlibFunctionException(msg)
    }
  }
}
