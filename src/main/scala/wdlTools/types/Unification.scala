package wdlTools.types

import wdlTools.types.TypeUtils.{isPrimitive, prettyFormatType}
import wdlTools.types.WdlTypes._
import TypeCheckingRegime._
import wdlTools.util.{AbstractBindings, Enum, Logger, TraceLevel}

case class VarTypeBindings(bindings: Map[Int, T])
    extends AbstractBindings[Int, T, VarTypeBindings](bindings) {
  override protected val elementType: String = "varType"

  override protected def copyFrom(values: Map[Int, T]): VarTypeBindings = {
    copy(bindings = values)
  }
}

object VarTypeBindings {
  lazy val empty: VarTypeBindings = VarTypeBindings(Map.empty[Int, T])
}

object Section extends Enum {
  type Section = Value
  val Input, Output, Call, Other = Value
}

case class UnificationContext(section: Section.Section = Section.Other,
                              inPlaceholder: Boolean = false)

object UnificationContext {
  def empty: UnificationContext = UnificationContext()
}

/**
  * Coercion priorities:
  * 0: an exact match of concrete types
  * 1: an exact match of type variables, e.g. T_Var(0) == T_Var(0)
  * 2: a non-exact match that is always allowed
  * 3: a non-exact match that is generally disallowed but is specifically
  *    allowed in the current context
  * 4: a non-exact match that is generally disallowed but is specifically
  *    allowed under the current type-checking regime
  * 5: a coercion involving T_Any
  */
object Priority extends Enum {
  type Priority = Value
  val Exact, AlwaysAllowed, VarMatch, ContextAllowed, RegimeAllowed, AnyMatch = Value
}

/**
  *
  * @param regime Type checking rules. Are we lenient or strict in checking coercions?
  */
case class Unification(regime: TypeCheckingRegime, logger: Logger = Logger.get) {

  /**
    * Determines whether one type can be coerced to another, within the given
    * context.
    * @param toType coerce to this type
    * @param fromType coerce from this type
    * @param ctx the context in which the coercion is performed - used for
    *            context-specific coersions
    * @return If the coercion can be performed, returns Some(priority), where
    *         priority is the preference of the coercion; otherwise, None.
    * @throws TypeUnificationException If the types cannot be unified
    */
  private def coerces(toType: T,
                      fromType: T,
                      ctx: UnificationContext): Option[Priority.Priority] = {

    def inner(innerTo: T,
              innerFrom: T,
              minPriority: Priority.Priority = Priority.Exact): Option[Priority.Priority] = {
      (innerTo, innerFrom) match {
        // primitive coercions that are always allowed
        case (l, r) if l == r   => Some(minPriority)
        case (T_Float, T_Int)   => Some(Enum.max(minPriority, Priority.AlwaysAllowed))
        case (T_File, T_String) => Some(Enum.max(minPriority, Priority.AlwaysAllowed))
        case (T_String, T_File) => Some(Enum.max(minPriority, Priority.AlwaysAllowed))

        // unwrap optional types
        case (T_Optional(l), T_Optional(r)) => inner(l, r, minPriority)

        // complex types
        case (T_Array(l, _), T_Array(r, _)) =>
          inner(l, r, minPriority)
        case (T_Map(kTo, vTo), T_Map(kFrom, vFrom)) =>
          val keyPriority = inner(kTo, kFrom, minPriority)
          val valuePriority = inner(vTo, vFrom, minPriority)
          if (keyPriority.isDefined && valuePriority.isDefined) {
            Some(Enum.max(keyPriority.get, valuePriority.get))
          } else {
            None
          }
        case (T_Pair(lTo, rTo), T_Pair(lFrom, rFrom)) =>
          val leftPriority = inner(lTo, lFrom, minPriority)
          val rightPriority = inner(rTo, rFrom, minPriority)
          if (leftPriority.isDefined && rightPriority.isDefined) {
            Some(Enum.max(leftPriority.get, rightPriority.get))
          } else {
            None
          }

        // structs are equivalent iff they have the same name
        case (T_Struct(nameTo, _), T_Struct(nameFrom, _)) if nameTo == nameFrom =>
          Some(minPriority)
        case (_: T_Struct, _: T_Struct) =>
          None

        // coercions from objects can't be checked - we allow them and expect an exception
        // during evaluation if the values are incompatible
        // TODO: in lenient mode, support Array[String] to Struct coercion as described in
        //  https://github.com/openwdl/wdl/issues/389
        case (T_Object, T_Object)    => Some(minPriority)
        case (_: T_Struct, T_Object) => Some(Enum.max(minPriority, Priority.AlwaysAllowed))

        // polymorphic types must have the same indices and compatible bounds
        case (T_Var(i, iBounds), T_Var(j, jBounds))
            if i == j && (iBounds.isEmpty || jBounds.isEmpty || (iBounds & jBounds).nonEmpty) =>
          Some(Enum.max(minPriority, Priority.VarMatch))

        // Other coercions are not generally allowed, but are either allowed
        // in specific contexts or are used often and so allowed under less
        // strict regimes
        case (T_String, T_Boolean | T_Int | T_Float) if ctx.inPlaceholder =>
          Some(minPriority)
        case (T_String, T_Boolean | T_Int | T_Float) if regime <= Lenient =>
          logger.trace(s"lenient coercion from ${innerFrom} to T_String")
          Some(Enum.max(minPriority, Priority.RegimeAllowed))
        case (T_Int, T_Float | T_String) if regime <= Lenient =>
          // we can allow this coercion assuming 1) a Float value that coerces
          // exactly to an int, or 2) a String value that can be parsed to an Int -
          // otherwise an exception should be thrown during evaluation
          logger.trace(s"lenient coercion from ${innerFrom} to T_Int")
          Some(Enum.max(minPriority, Priority.RegimeAllowed))
        case (T_Float, T_String) if regime <= Lenient =>
          // we can allow this coercion assuming a String value that can be parsed
          // to a Float - otherwise an exception should be thrown during evaluation
          logger.trace(s"lenient coercion from T_String to T_Float")
          Some(Enum.max(minPriority, Priority.RegimeAllowed))
        case (T_Optional(T_File), T_String) if ctx.section == Section.Output =>
          // Coercing a string to File is allowed within the output section,
          // since the value will be null if the file doesn't exist.
          Some(Enum.max(minPriority, Priority.ContextAllowed))
        case (T_Optional(l), r) if ctx.section == Section.Call =>
          // in a call, we can provide a non-optional value to an optional parameter
          inner(l, r, minPriority = Priority.ContextAllowed)
        case (T_Optional(l), r) if regime <= Moderate =>
          // T is coercible to T? - this isn't great, but it's necessary
          // since there is no function for doing the coercion explicitly
          logger.trace(s"moderate coercion from ${innerFrom} to optional")
          inner(l, r, minPriority = Priority.RegimeAllowed)
        case (T_Struct(structName, members), T_Map(T_String, valueType)) if regime <= Moderate =>
          // Coersions from Map to struct are not recommended and will fail unless the map key
          // type is String and the value type is coercible to all the struct member types
          val memberPriorities = members.view.mapValues { memberType =>
            inner(memberType, valueType, Enum.max(minPriority, Priority.ContextAllowed))
          }
          val invalidCoercions = memberPriorities.filter(_._2.isEmpty).keySet
          if (invalidCoercions.isEmpty) {
            logger.trace(s"moderate coercion from ${innerFrom} to ${structName}")
            Some(memberPriorities.values.flatten.max)
          } else {
            logger.trace(
                s"""invalid coercion from map to ${structName}: one or more member types ${invalidCoercions}

                   |not coercible from ${valueType}""".stripMargin
            )
            None
          }

        // T_Any coerces to anything
        case (_, T_Any) => Some(Enum.max(minPriority, Priority.AnyMatch))

        // cannot coerce to/from identifier
        case (_: T_Identifier, _) =>
          throw new RuntimeException(s"${toType} cannot be coerced to")
        case (_, _: T_Identifier) =>
          throw new RuntimeException(s"${toType} cannot be coerced from")

        case _ =>
          logger.trace(
              s"coercion from ${fromType} to ${toType} not allowed under regime ${regime} and/or in context ${ctx}",
              minLevel = TraceLevel.VVerbose
          )
          None
      }
    }
    inner(toType, fromType)
  }

  def isCoercibleTo(toType: T,
                    fromType: T,
                    ctx: UnificationContext = UnificationContext.empty): Boolean = {
    coerces(toType, fromType, ctx).isDefined
  }

  /**
    * Determines the least type that [t1] and [t2] are coercible to.
    * @param t1 first type to unify
    * @param t2 second type to unify
    * @param ctx UnificationContext
    * @param varTypes initial VarTypeBindings
    * @return (unifiedType, varTypes, priority), where unifiedType is the
    *         the least type that [t1] and [t2] are coercible to, varTypes
    *         is the updated type map for polymorphic placeholders, and
    *         priority is the priority value of the coercion.
    * @throws TypeUnificationException If the types cannot be unified
    * @example
    *    [Int?, Int]  -> Int?
    *
    * But we don't want to have:
    *    Array[String] s = ["a", 1, 3.1]
    * even if that makes sense, we don't want to have:
    *    Array\[Array\[String\]\] = [[1], ["2"], [1.1]]
    *
    * when calling a polymorphic function things get complicated.
    * For example:
    *    select_first([null, 6])
    * The signature for select_first is:
    *    Array[X?] -> X
    * we need to figure out that X is Int.
    */
  private def unify(
      t1: T,
      t2: T,
      ctx: UnificationContext,
      varTypes: VarTypeBindings = VarTypeBindings.empty
  ): (T, VarTypeBindings, Priority.Priority) = {
    def inner(
        x: T,
        y: T,
        vt: VarTypeBindings,
        minPriority: Priority.Priority
    ): (T, VarTypeBindings, Priority.Priority) = {
      if (x == y) {
        // exact match
        return (x, vt, minPriority)
      }
      if (isPrimitive(x) && isPrimitive(y)) {
        val priority = coerces(x, y, ctx)
        if (priority.nonEmpty) {
          // compatible primitive types
          return (x, vt, Enum.max(minPriority, priority.get))
        }
      }
      (x, y) match {
        case (T_Any, T_Any) =>
          (T_Any, vt, Priority.AnyMatch)
        case (x, T_Any) =>
          (x, vt, Priority.AnyMatch)
        case (T_Any, x) =>
          (x, vt, Priority.AnyMatch)
        case (T_Object, _: T_Struct) =>
          (T_Object, vt, Enum.max(minPriority, Priority.AlwaysAllowed))
        case (T_Optional(l), T_Optional(r)) =>
          val (t, newVarTypes, newMinPriority) = inner(l, r, vt, minPriority)
          (T_Optional(t), newVarTypes, newMinPriority)
        case (T_Optional(l), r) if ctx.section == Section.Call =>
          // in a call, we can provide a non-optional value to an optional parameter
          val (t, newVarTypes, newPriority) =
            inner(l, r, vt, Enum.max(minPriority, Priority.ContextAllowed))
          (T_Optional(t), newVarTypes, newPriority)
        case (T_Optional(l), r) if regime <= Moderate =>
          // T is coercible to T? - this isn't great, but it's necessary
          // since there is no function for doing the coercion explicitly
          logger.trace(s"moderate coercion from ${r} to optional")
          val (t, newVarTypes, newPriority) =
            inner(l, r, vt, Enum.max(minPriority, Priority.RegimeAllowed))
          (T_Optional(t), newVarTypes, newPriority)
        case (l, T_Optional(r)) =>
          val (t, newVarTypes, newPriority) =
            inner(l, r, vt, Enum.max(minPriority, Priority.AlwaysAllowed))
          (T_Optional(t), newVarTypes, newPriority)
        case (T_Array(l, _), T_Array(r, _)) =>
          val (t, newVarTypes, newPriority) = inner(l, r, vt, minPriority)
          (T_Array(t), newVarTypes, newPriority)
        case (T_Map(k1, v1), T_Map(k2, v2)) =>
          val (keyType, kVarTypes, keyPriority) = inner(k1, k2, vt, minPriority)
          val (valueType, kvVarTypes, valuePriority) = inner(v1, v2, kVarTypes, minPriority)
          (T_Map(keyType, valueType), kvVarTypes, Enum.max(keyPriority, valuePriority))
        case (T_Pair(l1, r1), T_Pair(l2, r2)) =>
          val (leftType, lVarTypes, leftPriority) = inner(l1, l2, vt, minPriority)
          val (rightType, lrVarTypes, rightPriority) = inner(r1, r2, lVarTypes, minPriority)
          (T_Pair(leftType, rightType), lrVarTypes, Enum.max(leftPriority, rightPriority))
        case (T_Identifier(l), T_Identifier(r)) if l == r =>
          // a user defined type
          (T_Identifier(l), vt, Enum.max(minPriority, Priority.AlwaysAllowed))
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
          (T_Var(i, bounds), vt, Enum.max(minPriority, Priority.VarMatch))
        case (a: T_Var, b: T_Var)
            if a.bounds.isEmpty || b.bounds.isEmpty || (a.bounds & b.bounds).nonEmpty =>
          // found a type equality between two variables
          val minPriority2 = Enum.max(minPriority, Priority.VarMatch)
          val (newVarTypes, newPriority) =
            (vt.get(a.index), vt.get(b.index)) match {
              case (None, None) =>
                (vt.add(a.index, b), minPriority2)
              case (None, Some(z: T_Var)) if a.bounds.isEmpty || (a.bounds & z.bounds).nonEmpty =>
                (vt.add(a.index, z), minPriority2)
              case (None, Some(z)) if a.bounds.isEmpty || a.bounds.contains(z) =>
                (vt.add(a.index, z), minPriority2)
              case (Some(z: T_Var), None) if b.bounds.isEmpty || (b.bounds & z.bounds).nonEmpty =>
                (vt.add(b.index, z), minPriority2)
              case (Some(z), None) if b.bounds.isEmpty || b.bounds.contains(z) =>
                (vt.add(b.index, z), minPriority2)
              case (Some(z), Some(w)) =>
                val (_, newVarTypes, newPriority) = inner(z, w, vt, minPriority2)
                (newVarTypes, newPriority)
            }
          (newVarTypes(a.index), newVarTypes, newPriority)
        case (a: T_Var, z) =>
          vt.get(a.index) match {
            case None if a.bounds.isEmpty || a.bounds.contains(z) =>
              // found a binding for a type variable
              (z, vt.add(a.index, z), Enum.max(minPriority, Priority.VarMatch))
            case Some(w) =>
              // a binding already exists, choose the more general type
              inner(w, z, vt, Enum.max(minPriority, Priority.VarMatch))
          }
        case _ =>
          throw new TypeUnificationException(
              s"There is no common type to which $x and $y are coercible"
          )
      }
    }
    inner(t1, t2, varTypes, Priority.Exact)
  }

  def apply(t1: T, t2: T, ctx: UnificationContext): T = {
    unify(t1, t2, ctx)._1
  }

  /**
    * Substitutes the type variables for the values in type 't'.
    * @param t type to subsitute
    * @param varTypes concrete types for polymorphic arguments
    * @return
    * @throws SubstitutionException if a type variable cannot be substituted
    *                               with the concrete type
    */
  private def substitute(t: T, varTypes: VarTypeBindings): T = {
    def inner(innerType: T): T = {
      innerType match {
        case T_String | T_File | T_Boolean | T_Int | T_Float => innerType
        case a: T_Var if !varTypes.contains(a.index) =>
          throw new SubstitutionException(
              s"type variable ${prettyFormatType(a)} does not have a binding"
          )
        case T_Var(index, _) => varTypes(index)
        case T_Pair(l, r)    => T_Pair(inner(l), inner(r))
        case T_Array(t, _)   => T_Array(inner(t))
        case T_Map(k, v)     => T_Map(inner(k), inner(v))
        case x: T_Struct     => x
        case T_Object        => T_Object
        case T_Optional(t1)  => T_Optional(inner(t1))
        case T_Any           => T_Any
        case other =>
          throw new SubstitutionException(
              s"Type ${prettyFormatType(other)} should not appear in this context"
          )
      }
    }
    inner(t)
  }

  /**
    * Unifies two function signatures and returns the output type.
    * For polymorphic functions, the best signature is the one with the lowest priority.
    * @param args1 first set of function parameter types
    * @param args2 second set of function parameter types
    * @param ctx UnificationContext
    * @return (unifiedTypes, varTypes, priority), where types is a Vector of
    *         the unified type for each argument, varTypes is the updated type
    *         map for polymorphic placeholders, and priority is the max of the
    *         priorities for indivisual arguments.
    * @throws TypeUnificationException If the types cannot be unified
    * @example
    * The signature for zip is:
    *    Array[Pair(X,Y)] zip(Array[X], Array[Y])
    * In order to type check a declaration like:
    *    Array\[Pair\[Int, String\]\] x  = zip([1, 2, 3], ["a", "b", "c"])
    * we solve for the X and Y type variables on the right hand
    * side. This should yield: { X : Int, Y : String }
    *
    * The inputs in this example are:
    *    x = [ T_Array(T_Var(0)), T_Array(T_Var(1)) ]
    *    y = [ T_Array(T_Int),  T_Array(T_String) ]
    *
    * The result is:
    *    T_Var(0) -> T_Int
    *    T_Var(1) -> T_String
    */
  def apply(
      args1: Vector[T],
      args2: Vector[T],
      output: T,
      ctx: UnificationContext
  ): (T, Priority.Priority) = {
    assert(args1.size == args2.size)
    if (args1.isEmpty) {
      (output, Priority.Exact)
    }
    val (priority, newVarTypes) =
      args1.zip(args2).foldLeft((Priority.Exact, VarTypeBindings.empty)) {
        case ((priority, vt), (lt, rt)) =>
          val (_, vt2, priority2) = unify(lt, rt, ctx, vt)
          (Enum.max(priority, priority2), vt2)
      }
    val unifiedType = substitute(output, newVarTypes)
    (unifiedType, priority)
  }

  /**
    * Unifies elements in a collection. For example, a vector of values.
    * @param types the types to unify
    * @param ctx UnificationContext
    * @return
    * @throws TypeUnificationException If the types cannot be unified
    */
  def apply(types: Iterable[T], ctx: UnificationContext): T = {
    assert(types.nonEmpty)
    val (unifiedType, _) = types.tail.foldLeft((types.head, VarTypeBindings.empty)) {
      case ((t, vt), t2) =>
        val (tUnified, ctxNew, _) = unify(t, t2, ctx, vt)
        (tUnified, ctxNew)
    }
    unifiedType
  }
}
