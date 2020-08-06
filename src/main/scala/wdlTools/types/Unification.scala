package wdlTools.types

import wdlTools.types.Utils.{isPrimitive, prettyFormatType}
import wdlTools.types.WdlTypes._
import TypeCheckingRegime._
import wdlTools.util.Logger

/**
  *
  * @param regime Type checking rules. Are we lenient or strict in checking coercions?
  */
case class Unification(regime: TypeCheckingRegime, logger: Logger = Logger.get) {
  // A value for each type variable.
  //
  // This is used when we have polymorphic types,
  // such as when calling standard library functions. We need to keep
  // track of the latest value for each type variable.
  type VarTypeContext = Map[Int, T]

  // TODO: we need to add priority to coercions, so that if stdlib
  //  is comparing multiple valid prototypes it can select one
  //  deterministically, rather than throw an exception
  private def isCoercibleTo2(left: T, right: T): Boolean = {
    (left, right) match {
      // primitive coercions that are always allowed
      case (l, r) if l == r              => true
      case (T_Float, T_Int)              => true
      case (T_String, T_String | T_File) => true
      case (T_File, T_String | T_File)   => true

      // Other coercions are not techincally allowed, but are
      // used often, so we allow them under Lenient regime
      case (T_Int, T_Float | T_String) if regime <= Lenient =>
        logger.trace(s"lenient coercion from ${right} to T_Int")
        true
      case (T_Float, T_String) if regime <= Lenient =>
        logger.trace(s"lenient coercion from ${right} to T_Float")
        true
      // TODO: these coercions to String should only be allowed 1. in Lenient
      //  regime, or 2. within string interpolation
      case (T_String, T_Boolean | T_Int | T_Float) => // if regime <= Lenient =>
        logger.trace(s"lenient coercion from ${right} to T_String")
        true

      // unwrap optional types
      case (T_Optional(l), T_Optional(r)) => isCoercibleTo2(l, r)
      // T is coercible to T?
      case (T_Optional(l), r) if regime <= Moderate =>
        logger.trace(s"moderate coercion from ${right} to optional")
        isCoercibleTo2(l, r)

      // complex types
      case (T_Array(l, _), T_Array(r, _)) =>
        isCoercibleTo2(l, r)
      case (T_Map(kl, vl), T_Map(kr, vr)) =>
        isCoercibleTo2(kl, kr) && isCoercibleTo2(vl, vr)
      case (T_Pair(l1, l2), T_Pair(r1, r2)) =>
        isCoercibleTo2(l1, r1) && isCoercibleTo2(l2, r2)

      // structs are equivalent iff they have the same name
      case (struct1: T_Struct, struct2: T_Struct) =>
        struct1.name == struct2.name

      // coercions to objects and structs can fail at runtime. We
      // are not thoroughly checking them here.
      // TODO: in lenient mode, support Array[String] to Struct coercion as described in
      //  https://github.com/openwdl/wdl/issues/389
      case (T_Object, T_Object)    => true
      case (_: T_Struct, T_Object) => true
      case (_: T_Struct, _: T_Map) => true

      // polymorphic types must have the same indices and compatible bounds
      case (T_Var(i, iBounds), T_Var(j, jBounds))
          if i == j && (iBounds.isEmpty || jBounds.isEmpty || (iBounds | jBounds).nonEmpty) =>
        true

      // T_Any coerces to anything
      case (_, T_Any) => true
      case _          => false
    }
  }

  def isCoercibleTo(left: T, right: T): Boolean = {
    if (left.isInstanceOf[T_Identifier]) {
      throw new RuntimeException(s"${left} should not appear here")
    }
    if (right.isInstanceOf[T_Identifier]) {
      throw new RuntimeException(s"${right} should not appear here")
    }
    (left, right) match {
      // List of special cases goes here

      // a type T can be coerced to a T?
      // I don't think this is such a great idea.
      case (T_Optional(l), r) if l == r => true

      // normal cases
      case (_, _) =>
        isCoercibleTo2(left, right)
    }
  }

  // The least type that [x] and [y] are coercible to.
  // For example:
  //    [Int?, Int]  -> Int?
  //
  // But we don't want to have:
  //    Array[String] s = ["a", 1, 3.1]
  // even if that makes sense, we don't want to have:
  //    Array[Array[String]] = [[1], ["2"], [1.1]]
  //
  //
  // when calling a polymorphic function things get complicated.
  // For example:
  //    select_first([null, 6])
  // The signature for select first is:
  //    Array[X?] -> X
  // we need to figure out that X is Int.
  //
  //
  def unify(t1: T, t2: T, varTypeContext: VarTypeContext): (T, VarTypeContext, Boolean) = {
    // if they are an exact match then obviously they are compatible
    if (t1 == t2) {
      return (t1, varTypeContext, true)
    }
    def inner(x: T, y: T, ctx: VarTypeContext): (T, VarTypeContext) = {
      (x, y) match {
        // base case, primitive types
        case (_, _) if isPrimitive(x) && isPrimitive(y) && isCoercibleTo(x, y) =>
          (x, ctx)
        case (T_Any, T_Any) => (T_Any, ctx)
        case (x, T_Any)     => (x, ctx)
        case (T_Any, x)     => (x, ctx)

        case (T_Object, T_Object)    => (T_Object, ctx)
        case (T_Object, _: T_Struct) => (T_Object, ctx)

        case (T_Optional(l), T_Optional(r)) =>
          val (t, ctx2) = inner(l, r, ctx)
          (T_Optional(t), ctx2)

        // These two cases are really questionable to me. We are allowing an X to
        // become an X?
        case (T_Optional(l), r) =>
          val (t, ctx2) = inner(l, r, ctx)
          (T_Optional(t), ctx2)
        case (l, T_Optional(r)) =>
          val (t, ctx2) = inner(l, r, ctx)
          (T_Optional(t), ctx2)

        case (T_Array(l, _), T_Array(r, _)) =>
          val (t, ctx2) = inner(l, r, ctx)
          (T_Array(t), ctx2)
        case (T_Map(k1, v1), T_Map(k2, v2)) =>
          val (kt, ctx2) = inner(k1, k2, ctx)
          val (vt, ctx3) = inner(v1, v2, ctx2)
          (T_Map(kt, vt), ctx3)
        case (T_Pair(l1, r1), T_Pair(l2, r2)) =>
          val (lt, ctx2) = inner(l1, l2, ctx)
          val (rt, ctx3) = inner(r1, r2, ctx2)
          (T_Pair(lt, rt), ctx3)
        case (T_Identifier(l), T_Identifier(r)) if l == r =>
          // a user defined type
          (T_Identifier(l), ctx)
        case (T_Var(i, iBounds), T_Var(j, jBounds)) if i == j =>
          val bounds: Set[T] = (iBounds, jBounds) match {
            case (a, b) if a.nonEmpty && b.nonEmpty =>
              val isect = iBounds & jBounds
              if (isect.isEmpty) {
                throw new TypeUnificationException(
                    s"""Type variables have non-intersecting bounds: 
                       |${iBounds} != ${jBounds}""".stripMargin
                )
              }
              isect
            case (a, _) if a.nonEmpty => a
            case (_, b) if b.nonEmpty => b
            case _                    => Set.empty
          }
          (T_Var(i, bounds), ctx)

        case (a: T_Var, b: T_Var)
            if a.bounds.isEmpty || b.bounds.isEmpty || (a.bounds & b.bounds).nonEmpty =>
          // found a type equality between two variables
          val ctx3: VarTypeContext = (ctx.get(a.index), ctx.get(b.index)) match {
            case (None, None) =>
              ctx + (a.index -> b)
            case (None, Some(z: T_Var)) if a.bounds.isEmpty || (a.bounds & z.bounds).nonEmpty =>
              ctx + (a.index -> z)
            case (None, Some(z)) if a.bounds.isEmpty || a.bounds.contains(z) =>
              ctx + (a.index -> z)
            case (Some(z: T_Var), None) if b.bounds.isEmpty || (b.bounds & z.bounds).nonEmpty =>
              ctx + (b.index -> z)
            case (Some(z), None) if b.bounds.isEmpty || b.bounds.contains(z) =>
              ctx + (b.index -> z)
            case (Some(z), Some(w)) =>
              val (_, ctx2) = inner(z, w, ctx)
              ctx2
          }
          (ctx3(a.index), ctx3)

        case (a: T_Var, z) =>
          ctx.get(a.index) match {
            case None if a.bounds.isEmpty || a.bounds.contains(z) =>
              // found a binding for a type variable
              (z, ctx + (a.index -> z))
            case Some(w) =>
              // a binding already exists, choose the more general type
              inner(w, z, ctx)
          }

        case _ =>
          throw new TypeUnificationException(s"Types $x and $y do not match")
      }
    }
    val (unifiedType, ctx) = inner(t1, t2, varTypeContext)
    (unifiedType, ctx, false)
  }

  // Unify a set of type pairs, and return a solution for the type
  // variables. If the types cannot be unified throw a TypeUnification exception.
  //
  // For example the signature for zip is:
  //    Array[Pair(X,Y)] zip(Array[X], Array[Y])
  // In order to type check a declaration like:
  //    Array[Pair[Int, String]] x  = zip([1, 2, 3], ["a", "b", "c"])
  // we solve for the X and Y type variables on the right hand
  // side. This should yield: { X : Int, Y : String }
  //
  // The inputs in this example are:
  //    x = [ T_Array(T_Var(0)), T_Array(T_Var(1)) ]
  //    y = [ T_Array(T_Int),  T_Array(T_String) ]
  //
  // The result is:
  //    T_Var(0) -> T_Int
  //    T_Var(1) -> T_String
  //
  def unifyFunctionArguments(x: Vector[T],
                             y: Vector[T],
                             ctx: VarTypeContext): (Vector[(T, Boolean)], VarTypeContext) = {
    x.zip(y).foldLeft((Vector.empty[(T, Boolean)], ctx)) {
      case ((tVec, ctx), (lt, rt)) =>
        val (t, ctx2, exact) = unify(lt, rt, ctx)
        (tVec :+ (t, exact), ctx2)
    }
  }

  // Unify elements in a collection. For example, a vector of values.
  def unifyCollection(tVec: Iterable[T], ctx: VarTypeContext): (T, VarTypeContext) = {
    assert(tVec.nonEmpty)
    tVec.tail.foldLeft((tVec.head, ctx)) {
      case ((t, ctx), t2) =>
        val (tUnified, ctxNew, _) = unify(t, t2, ctx)
        (tUnified, ctxNew)
    }
  }

  // substitute the type variables for the values in type 't'
  def substitute(t: T, typeBindings: VarTypeContext): T = {
    def sub(t: T): T = {
      t match {
        case T_String | T_File | T_Boolean | T_Int | T_Float => t
        case a: T_Var if !typeBindings.contains(a.index) =>
          throw new SubstitutionException(
              s"type variable ${prettyFormatType(a)} does not have a binding"
          )
        case a: T_Var       => typeBindings(a.index)
        case x: T_Struct    => x
        case T_Pair(l, r)   => T_Pair(sub(l), sub(r))
        case T_Array(t, _)  => T_Array(sub(t))
        case T_Map(k, v)    => T_Map(sub(k), sub(v))
        case T_Object       => T_Object
        case T_Optional(t1) => T_Optional(sub(t1))
        case T_Any          => T_Any
        case other =>
          throw new SubstitutionException(
              s"Type ${prettyFormatType(other)} should not appear in this context"
          )
      }
    }
    sub(t)
  }
}
