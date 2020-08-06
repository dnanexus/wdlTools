package wdlTools.types

import WdlTypes.{T_Function2, T_Var, _}
import wdlTools.syntax.{Operator, WdlVersion}
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.util.{Logger, TraceLevel}

case class Stdlib(regime: TypeCheckingRegime, version: WdlVersion, logger: Logger = Logger.get) {
  private val unify = Unification(regime)

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

  private def unaryNumericPrototypes(funcName: String): Vector[T_Function] = {
    Vector(
        T_Function1(funcName, T_Int, T_Int),
        T_Function1(funcName, T_Float, T_Float)
    )
  }
  private def comparisonPrototypes(funcName: String): Vector[T_Function] = {
    Vector(
        T_Function2(funcName, T_Boolean, T_Boolean, T_Boolean),
        T_Function2(funcName, T_Int, T_Int, T_Boolean),
        T_Function2(funcName, T_Float, T_Float, T_Boolean),
        T_Function2(funcName, T_String, T_String, T_Boolean),
        T_Function2(funcName, T_Int, T_Float, T_Boolean),
        T_Function2(funcName, T_Float, T_Int, T_Boolean)
    )
  }
  private def binaryNumericPrototypes(funcName: String): Vector[T_Function] = {
    Vector(
        T_Function2(funcName, T_Int, T_Int, T_Int),
        T_Function2(funcName, T_Float, T_Float, T_Float),
        T_Function2(funcName, T_Int, T_Float, T_Float),
        T_Function2(funcName, T_Float, T_Int, T_Float)
    )
  }
  // The + operator is overloaded for string arguments. If both arguments are non-optional,
  // then the return type is non-optional. Within an interpolation, if either argument type
  // is optional, then the return type is optional
  private def optionalStringAdditionPrototypes(a: T, b: T, result: T): Vector[T_Function] = {
    Vector(
        T_Function2(Operator.Addition.name, T_Optional(a), b, T_Optional(result)),
        T_Function2(Operator.Addition.name, a, T_Optional(b), T_Optional(result)),
        T_Function2(Operator.Addition.name, T_Optional(a), T_Optional(b), T_Optional(result))
    )
  }
  private def stringAdditionPrototypes(a: T, b: T, result: T): Vector[T_Function] = {
    Vector(
        T_Function2(Operator.Addition.name, a, b, result)
    ) ++ optionalStringAdditionPrototypes(a, b, result)
  }

  private lazy val draft2Prototypes: Vector[T_Function] = Vector(
      // unary numeric operators
      unaryNumericPrototypes(Operator.UnaryPlus.name),
      unaryNumericPrototypes(Operator.UnaryMinus.name),
      // logical operators
      Vector(
          T_Function1(Operator.LogicalNot.name, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalOr.name, T_Boolean, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalAnd.name, T_Boolean, T_Boolean, T_Boolean)
      ),
      // comparison operators
      // equal/not-equal comparisons are allowed for all primitive types
      // prior to V2 Booleans and Strings could be compared by >/<; it is
      // not explicitly stated in the spec, we assume
      // * true > false
      // * Strings are ordered lexicographically using Unicode code point number
      //   for comparison of individual characters
      comparisonPrototypes(Operator.Equality.name),
      comparisonPrototypes(Operator.Inequality.name),
      comparisonPrototypes(Operator.LessThan.name),
      comparisonPrototypes(Operator.LessThanOrEqual.name),
      comparisonPrototypes(Operator.GreaterThan.name),
      comparisonPrototypes(Operator.GreaterThanOrEqual.name),
      // equal/not-equal is allowed for File-String
      // also, it is not explicitly stated in the spec, but we allow
      // comparisons for any operands of the same type
      Vector(
          T_Function2(Operator.Equality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Inequality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Equality.name, T_File, T_String, T_Boolean),
          T_Function2(Operator.Inequality.name, T_File, T_String, T_Boolean),
          T_Function2(Operator.Equality.name, T_Var(0), T_Var(0), T_Boolean),
          T_Function2(Operator.Inequality.name, T_Var(0), T_Var(0), T_Boolean)
      ),
      // the + function is overloaded for string expressions
      // it is omitted from the spec, but we allow String + Boolean as well
      stringAdditionPrototypes(T_File, T_File, T_File),
      stringAdditionPrototypes(T_File, T_String, T_File),
      stringAdditionPrototypes(T_String, T_File, T_File),
      stringAdditionPrototypes(T_String, T_String, T_String),
      stringAdditionPrototypes(T_Int, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Int, T_String),
      stringAdditionPrototypes(T_Float, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Float, T_String),
      stringAdditionPrototypes(T_String, T_Boolean, T_String),
      stringAdditionPrototypes(T_Boolean, T_String, T_String),
      // binary numeric operators
      binaryNumericPrototypes(Operator.Addition.name),
      binaryNumericPrototypes(Operator.Subtraction.name),
      binaryNumericPrototypes(Operator.Multiplication.name),
      binaryNumericPrototypes(Operator.Division.name),
      binaryNumericPrototypes(Operator.Remainder.name),
      // standard library functions
      Vector(
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
          T_Function2("zip",
                      T_Array(T_Var(0)),
                      T_Array(T_Var(1)),
                      T_Array(T_Pair(T_Var(0), T_Var(1)))),
          // Array[Pair(X,Y)] cross(Array[X], Array[Y])
          T_Function2("cross",
                      T_Array(T_Var(0)),
                      T_Array(T_Var(1)),
                      T_Array(T_Pair(T_Var(0), T_Var(1)))),
          // Integer length(Array[X])
          T_Function1("length", T_Array(T_Var(0)), T_Int),
          // Array[X] flatten(Array[Array[X]])
          T_Function1("flatten", T_Array(T_Array(T_Var(0))), T_Array(T_Var(0))),
          T_Function2("prefix",
                      T_String,
                      T_Array(T_Var(0, Utils.PrimitiveTypes)),
                      T_Array(T_String)),
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
  ).flatten

  private lazy val v1Prototypes: Vector[T_Function] = Vector(
      // unary numeric operators
      unaryNumericPrototypes(Operator.UnaryPlus.name),
      unaryNumericPrototypes(Operator.UnaryMinus.name),
      // logical operators
      Vector(
          T_Function1(Operator.LogicalNot.name, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalOr.name, T_Boolean, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalAnd.name, T_Boolean, T_Boolean, T_Boolean)
      ),
      // comparison operators
      // equal/not-equal comparisons are allowed for all primitive types
      // prior to V2 Booleans and Strings could be compared by >/<; it is
      // not explicitly stated in the spec, we assume
      // * true > false
      // * Strings are ordered lexicographically using Unicode code point number
      //   for comparison of individual characters
      comparisonPrototypes(Operator.Equality.name),
      comparisonPrototypes(Operator.Inequality.name),
      comparisonPrototypes(Operator.LessThan.name),
      comparisonPrototypes(Operator.LessThanOrEqual.name),
      comparisonPrototypes(Operator.GreaterThan.name),
      comparisonPrototypes(Operator.GreaterThanOrEqual.name),
      // equal/not-equal is allowed for File-String
      // also, it is not explicitly stated in the spec, but we allow
      // comparisons for any operands of the same type
      Vector(
          T_Function2(Operator.Equality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Inequality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Equality.name, T_File, T_String, T_Boolean),
          T_Function2(Operator.Inequality.name, T_File, T_String, T_Boolean),
          T_Function2(Operator.Equality.name, T_Var(0), T_Var(0), T_Boolean),
          T_Function2(Operator.Inequality.name, T_Var(0), T_Var(0), T_Boolean)
      ),
      // the + function is overloaded for string expressions
      // it is omitted from the spec, but we allow String + Boolean as well
      stringAdditionPrototypes(T_File, T_File, T_File),
      stringAdditionPrototypes(T_File, T_String, T_File),
      stringAdditionPrototypes(T_String, T_File, T_File),
      stringAdditionPrototypes(T_String, T_String, T_String),
      stringAdditionPrototypes(T_Int, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Int, T_String),
      stringAdditionPrototypes(T_Float, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Float, T_String),
      stringAdditionPrototypes(T_String, T_Boolean, T_String),
      stringAdditionPrototypes(T_Boolean, T_String, T_String),
      // binary numeric operators
      binaryNumericPrototypes(Operator.Addition.name),
      binaryNumericPrototypes(Operator.Subtraction.name),
      binaryNumericPrototypes(Operator.Multiplication.name),
      binaryNumericPrototypes(Operator.Division.name),
      binaryNumericPrototypes(Operator.Remainder.name),
      // standard library functions
      Vector(
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
          T_Function2("zip",
                      T_Array(T_Var(0)),
                      T_Array(T_Var(1)),
                      T_Array(T_Pair(T_Var(0), T_Var(1)))),
          // Array[Pair(X,Y)] cross(Array[X], Array[Y])
          T_Function2("cross",
                      T_Array(T_Var(0)),
                      T_Array(T_Var(1)),
                      T_Array(T_Pair(T_Var(0), T_Var(1)))),
          // Integer length(Array[X])
          T_Function1("length", T_Array(T_Var(0)), T_Int),
          // Array[X] flatten(Array[Array[X]])
          T_Function1("flatten", T_Array(T_Array(T_Var(0))), T_Array(T_Var(0))),
          T_Function2("prefix",
                      T_String,
                      T_Array(T_Var(0, Utils.PrimitiveTypes)),
                      T_Array(T_String)),
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
  ).flatten

  private def comparisonPrototypesV2(funcName: String): Vector[T_Function] = {
    Vector(
        T_Function2(funcName, T_Int, T_Int, T_Boolean),
        T_Function2(funcName, T_Int, T_Float, T_Boolean),
        T_Function2(funcName, T_Float, T_Float, T_Boolean),
        T_Function2(funcName, T_Float, T_Int, T_Boolean),
        T_Function2(funcName, T_String, T_String, T_Boolean)
    )
  }

  private lazy val v2Prototypes: Vector[T_Function] = Vector(
      // unary numeric operators
      unaryNumericPrototypes(Operator.UnaryMinus.name),
      // logical operators
      Vector(
          T_Function1(Operator.LogicalNot.name, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalOr.name, T_Boolean, T_Boolean, T_Boolean),
          T_Function2(Operator.LogicalAnd.name, T_Boolean, T_Boolean, T_Boolean)
      ),
      // comparison operators
      comparisonPrototypesV2(Operator.Equality.name),
      comparisonPrototypesV2(Operator.Inequality.name),
      comparisonPrototypesV2(Operator.LessThan.name),
      comparisonPrototypesV2(Operator.LessThanOrEqual.name),
      comparisonPrototypesV2(Operator.GreaterThan.name),
      comparisonPrototypesV2(Operator.GreaterThanOrEqual.name),
      // it is not explicitly stated in the spec, but we allow equal/not-equal
      // comparisons for any operands of the same type
      Vector(
          T_Function2(Operator.Equality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Equality.name, T_Directory, T_Directory, T_Boolean),
          T_Function2(Operator.Inequality.name, T_File, T_File, T_Boolean),
          T_Function2(Operator.Inequality.name, T_Directory, T_Directory, T_Boolean),
          T_Function2(Operator.Equality.name, T_Var(0), T_Var(0), T_Boolean),
          T_Function2(Operator.Inequality.name, T_Var(0), T_Var(0), T_Boolean)
      ),
      // the + function is overloaded for string expressions
      // even worse, it has different semantics within an interpolation vs elsewhere
      // https://github.com/openwdl/wdl/blob/main/versions/development/SPEC.md#interpolating-and-concatenating-optional-strings
      Vector(
          // if both arguments are non-optional, then the return type is non-optional
          // v2 inverts the argument order of File/String concatenation - we allow
          // both in all versions
          T_Function2(Operator.Addition.name, T_File, T_String, T_File),
          T_Function2(Operator.Addition.name, T_String, T_File, T_File),
          T_Function2(Operator.Addition.name, T_String, T_String, T_String)
      ),
      // binary numeric operators
      binaryNumericPrototypes(Operator.Addition.name),
      binaryNumericPrototypes(Operator.Subtraction.name),
      binaryNumericPrototypes(Operator.Multiplication.name),
      binaryNumericPrototypes(Operator.Division.name),
      binaryNumericPrototypes(Operator.Remainder.name),
      // standard library functions
      Vector(
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
          T_Function2("zip",
                      T_Array(T_Var(0)),
                      T_Array(T_Var(1)),
                      T_Array(T_Pair(T_Var(0), T_Var(1)))),
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
          T_Function2("prefix",
                      T_String,
                      T_Array(T_Var(0, Utils.PrimitiveTypes)),
                      T_Array(T_String)),
          T_Function2("suffix",
                      T_String,
                      T_Array(T_Var(0, Utils.PrimitiveTypes)),
                      T_Array(T_String)),
          T_Function1("quote", T_Array(T_Var(0, Utils.PrimitiveTypes)), T_Array(T_String)),
          T_Function1("squote", T_Array(T_Var(0, Utils.PrimitiveTypes)), T_Array(T_String)),
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
          T_Function1("glob", T_String, T_Array(T_File)),
          T_Function2("sep", T_String, T_Array(T_String), T_String)
      )
  ).flatten

  private lazy val v2PlaceholderPrototypes: Vector[T_Function] = Vector(
      optionalStringAdditionPrototypes(T_File, T_String, T_File),
      optionalStringAdditionPrototypes(T_String, T_File, T_File),
      optionalStringAdditionPrototypes(T_String, T_String, T_String),
      // these concatenations are no longer allowed generally, but
      // I believe they should be allowed within placeholders - there
      // is an open discussion https://github.com/openwdl/wdl/issues/391
      stringAdditionPrototypes(T_Int, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Int, T_String),
      stringAdditionPrototypes(T_Float, T_String, T_String),
      stringAdditionPrototypes(T_String, T_Float, T_String),
      stringAdditionPrototypes(T_String, T_Boolean, T_String),
      stringAdditionPrototypes(T_Boolean, T_String, T_String)
  ).flatten

  // choose the standard library prototypes according to the WDL version
  private def protoTable(inPlaceholder: Boolean): Vector[T_Function] = version match {
    case WdlVersion.Draft_2 =>
      draft2Prototypes
    case WdlVersion.V1 =>
      v1Prototypes
    case WdlVersion.V2 if inPlaceholder || regime <= TypeCheckingRegime.Lenient =>
      v2Prototypes ++ v2PlaceholderPrototypes
    case WdlVersion.V2 =>
      v2Prototypes
    case other => throw new RuntimeException(s"Unsupported WDL version ${other}")
  }

  // build a mapping from a function name to all of its prototypes.
  // Some functions are overloaded, so they may have several.
  private def funcProtoMap(inPlaceholder: Boolean): Map[String, Vector[T_Function]] = {
    protoTable(inPlaceholder).foldLeft(Map.empty[String, Vector[T_Function]]) {
      case (accu, funcDesc: T_Function) =>
        accu.get(funcDesc.name) match {
          case None =>
            accu + (funcDesc.name -> Vector(funcDesc))
          case Some(protoVec: Vector[T_Function]) =>
            accu + (funcDesc.name -> (protoVec :+ funcDesc))
        }
    }
  }

  /**
    * Evaluate the output type of a function. This may require calculation because
    * some functions are polymorphic in their inputs.
    * @param funcProto The function prototype
    * @param inputTypes The actual input types
    * @return an optional (T, Boolean), where T is the actual output type, and the
    *         second argument specifies whether all types in the signature were
    *         matched exactly by the input types.
    */
  private def evalOnePrototype(funcProto: T_Function,
                               inputTypes: Vector[T]): Option[(T, Boolean)] = {
    val arity = inputTypes.size
    val args = (arity, funcProto) match {
      case (0, T_Function0(_, _))                   => Vector.empty
      case (1, T_Function1(_, arg1, _))             => Vector(arg1)
      case (2, T_Function2(_, arg1, arg2, _))       => Vector(arg1, arg2)
      case (3, T_Function3(_, arg1, arg2, arg3, _)) => Vector(arg1, arg2, arg3)
      case (_, _)                                   => return None
    }
    try {
      val (unifiedInputs, ctx) = unify.unifyFunctionArguments(args, inputTypes, Map.empty)
      val allExact = unifiedInputs.forall(_._2)
      val unifiedOutput = unify.substitute(funcProto.output, ctx)
      Some((unifiedOutput, allExact))
    } catch {
      case _: TypeUnificationException =>
        None
    }
  }

  /**
    * Determines whether `funcName` is valid and whether there is a signature that
    * matches `inputTypes`.
    * @param funcName The function name
    * @param inputTypes The input types
    * @param inPlaceholder whether the function is being evaluated within a placeholder -
    *                      this currently only pertains to the special handling of
    *                      concatenation of optional types in V2+.
    * @return
    */
  def apply(funcName: String, inputTypes: Vector[T], inPlaceholder: Boolean): (T, T_Function) = {
    val candidates: Vector[T_Function] = funcProtoMap(inPlaceholder).get(funcName) match {
      case None =>
        throw new StdlibFunctionException(s"No function named ${funcName} in the standard library")
      case Some(protoVec) =>
        protoVec
    }
    // The function may be overloaded, taking several types of inputs. Try to match all
    // prototypes against the input, preferring the one with exactly matching inputs (if any)
    val viableCandidates: Vector[((T, T_Function), Boolean)] = candidates.flatMap { funcProto =>
      try {
        evalOnePrototype(funcProto, inputTypes) match {
          case None             => None
          case Some((t, exact)) => Some(((t, funcProto), exact))
        }
      } catch {
        case e: SubstitutionException =>
          throw new StdlibFunctionException(e.getMessage)
      }
    }
    viableCandidates.partition(_._2) match {
      case (Vector(result), _) =>
        // one exact match
        logger.trace(
            s"selected exact match prototype ${result}",
            minLevel = TraceLevel.VVerbose
        )
        result._1
      case (Vector(), Vector(result)) =>
        // one coerced match
        logger.trace(
            s"selected coerced prototype ${result}",
            minLevel = TraceLevel.VVerbose
        )
        result._1
      case (Vector(), Vector()) =>
        // no matches
        val inputsStr = inputTypes.map(Utils.prettyFormatType).mkString("\n")
        val candidatesStr = candidates.map(Utils.prettyFormatType(_)).mkString("\n")
        val msg = s"""|Invoking stdlib function ${funcName} with badly typed arguments
                      |${candidatesStr}
                      |inputs: ${inputsStr}
                      |""".stripMargin
        throw new StdlibFunctionException(msg)
      case (v1, v2) =>
        // Match more than one prototype.
        val v = if (v1.nonEmpty) v1 else v2
        val prototypeDescriptions = v
          .map {
            case ((_, funcSig), _) =>
              Utils.prettyFormatType(funcSig)
          }
          .mkString("\n")
        val msg = s"""|Call to ${funcName} matches ${v.size} prototypes
                      |inputTypes: ${inputTypes}
                      |prototypes:
                      |${prototypeDescriptions}
                      |""".stripMargin
        throw new StdlibFunctionException(msg)
    }
  }
}
