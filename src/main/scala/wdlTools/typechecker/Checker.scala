package wdlTools.typechecker

import wdlTools.util.Util.Conf
import wdlTools.syntax.AbstractSyntax._
import Base._

case class Checker(stdlib: Stdlib, conf: Conf) {

  type Context = Map[String, Type]

  private def typeEvalMathOp(expr: Expr, ctx: Context): Type = {
    val t = typeEval(expr, ctx)
    t match {
      case TypeInt   => TypeInt
      case TypeFloat => TypeFloat
      case _         => throw new Exception(s"${expr} must be an integer or a float")
    }
  }

  // The add operation is overloaded.
  // 1) The result of adding two integers is an integer
  // 2)    -"-                   floats   -"-   float
  // 3)    -"-                   strings  -"-   string
  private def typeEvalAdd(a: Expr, b: Expr, ctx: Context): Type = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (TypeInt, TypeInt)     => TypeInt
      case (TypeFloat, TypeInt)   => TypeFloat
      case (TypeInt, TypeFloat)   => TypeFloat
      case (TypeFloat, TypeFloat) => TypeFloat

      // if we are adding strings, the result is a string
      case (TypeString, TypeString) => TypeString
      case (TypeString, TypeInt)    => TypeString
      case (TypeString, TypeFloat)  => TypeString
      case (TypeInt, TypeString)    => TypeString
      case (TypeFloat, TypeString)  => TypeString

      // adding files is equivalent to concatenating paths
      case (TypeFile, TypeFile) => TypeFile

      case (_, _) => throw new Exception(s"Expressions ${a} and ${b} cannot be added")
    }
  }

  private def typeEvalMathOp(a: Expr, b: Expr, ctx: Context): Type = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (TypeInt, TypeInt)     => TypeInt
      case (TypeFloat, TypeInt)   => TypeFloat
      case (TypeInt, TypeFloat)   => TypeFloat
      case (TypeFloat, TypeFloat) => TypeFloat
      case (_, _)                 => throw new Exception(s"Expressions ${a} and ${b} must be integers or floats")
    }
  }

  private def typeEvalLogicalOp(expr: Expr, ctx: Context): Type = {
    val t = typeEval(expr, ctx)
    t match {
      case TypeBoolean => TypeBoolean
      case other =>
        throw new Exception(s"${expr} must be a boolean, it is ${other}")
    }
  }

  private def typeEvalLogicalOp(a: Expr, b: Expr, ctx: Context): Type = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (TypeBoolean, TypeBoolean) => TypeBoolean
      case (_, _)                     => throw new Exception(s"${a} and ${b} must have boolean type")
    }
  }

  private def typeEvalCompareOp(a: Expr, b: Expr, ctx: Context): Type = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    if (at == bt) {
      // These could be complex types, such as Array[Array[Int]].
      return TypeBoolean
    }

    // Even if the types are not the same, there are cases where they can
    // be compared.
    (at, bt) match {
      case (TypeInt, TypeFloat) => TypeBoolean
      case (TypeFloat, TypeInt) => TypeBoolean
      case (_, _) =>
        throw new Exception(s"Expressions ${a} and ${b} must have the same type")
    }
  }

  // Figure out what the type of an expression is.
  //
  private def typeEval(expr: Expr, ctx: Context): Type = {
    expr match {
      // base cases, primitive types
      case _: ValueString  => TypeString
      case _: ValueFile    => TypeFile
      case _: ValueBoolean => TypeBoolean
      case _: ValueInt     => TypeInt
      case _: ValueFloat   => TypeFloat

      // an identifier has to be bound to a known type
      case ExprIdentifier(id) =>
        ctx.get(id) match {
          case None    => throw new RuntimeException(s"Identifier ${id} is not defined")
          case Some(t) => t
        }

      // All the sub-exressions have to be strings, or coercible to strings
      case ExprCompoundString(vec) =>
        vec.foreach {
          case subExpr =>
            val t = typeEval(subExpr, ctx)
            if (!isCoercibleTo(TypeString, t))
              throw new Exception(s"Type ${t} is not coercible to string")
        }
        TypeString

      case ExprPair(l, r)                => TypePair(typeEval(l, ctx), typeEval(r, ctx))
      case ExprArray(vec) if vec.isEmpty =>
        // The array is empty, we can't tell what the array type is.
        TypeArray(TypeUnknown, false)

      case ExprArray(vec) =>
        val vecTypes = vec.map(typeEval(_, ctx))
        val t = vecTypes.head
        if (!vecTypes.tail.forall(isCoercibleTo(_, t)))
          throw new Exception(s"Array elements do not all have type ${t}")
        TypeArray(t, false)

      case ExprMap(m) if m.isEmpty =>
        // The map type is unknown
        TypeMap(TypeUnknown, TypeUnknown)

      case ExprMap(m) =>
        // figure out the types from the first element
        val mTypes: Map[Type, Type] = m.map {
          case (k, v) => typeEval(k, ctx) -> typeEval(v, ctx)
        }
        val tk = mTypes.keys.head
        if (!mTypes.keys.tail.forall(isCoercibleTo(_, tk)))
          throw new Exception(s"Map keys do not all have type ${tk}")
        val tv = mTypes.values.head
        if (!mTypes.values.tail.forall(isCoercibleTo(_, tv)))
          throw new Exception(s"Map values do not all have type ${tv}")

        TypeMap(tk, tv)

      // These are expressions like:
      // ${true="--yes" false="--no" boolean_value}
      case ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr) =>
        val tType = typeEval(t, ctx)
        val fType = typeEval(f, ctx)
        if (fType != tType)
          throw new Exception(s"subexpressions ${t} and ${f} in ${expr} must have the same type")
        val tv = typeEval(value, ctx)
        if (tv != TypeBoolean)
          throw new Exception(
              s"${value} in ${expr} should have boolean type, it has type ${tv} instead"
          )
        tType

      // An expression like:
      // ${default="foo" optional_value}
      case ExprPlaceholderDefault(default: Expr, value: Expr) =>
        val vt = typeEval(value, ctx)
        val dt = typeEval(default, ctx)
        vt match {
          case TypeOptional(vt2) if vt2 == dt => dt
          case _ =>
            throw new Exception(s"""|Subxpression ${value} in ${expr} must have type optional(${dt})
                                    |it has type ${vt} instead""".stripMargin.replaceAll("\n", " "))
        }

      // An expression like:
      // ${sep=", " array_value}
      case ExprPlaceholderSep(sep: Expr, value: Expr) =>
        val sepType = typeEval(sep, ctx)
        if (sepType != TypeString)
          throw new Exception(s"separator ${sep} in ${expr} must have string type")
        val vt = typeEval(value, ctx)
        vt match {
          case TypeArray(t, _) if isCoercibleTo(TypeString, t) =>
            TypeString
          case other =>
            throw new Exception(
                s"expression ${value} should be of type Array[String], but it is ${other}"
            )
        }

      // math operators on one argument
      case ExprUniraryPlus(value)  => typeEvalMathOp(value, ctx)
      case ExprUniraryMinus(value) => typeEvalMathOp(value, ctx)

      // logical operators
      case ExprLor(a: Expr, b: Expr)  => typeEvalLogicalOp(a, b, ctx)
      case ExprLand(a: Expr, b: Expr) => typeEvalLogicalOp(a, b, ctx)
      case ExprNegate(value: Expr)    => typeEvalLogicalOp(value, ctx)

      // equality comparisons
      case ExprEqeq(a: Expr, b: Expr) => typeEvalCompareOp(a, b, ctx)
      case ExprNeq(a: Expr, b: Expr)  => typeEvalCompareOp(a, b, ctx)
      case ExprLt(a: Expr, b: Expr)   => typeEvalCompareOp(a, b, ctx)
      case ExprGte(a: Expr, b: Expr)  => typeEvalCompareOp(a, b, ctx)
      case ExprLte(a: Expr, b: Expr)  => typeEvalCompareOp(a, b, ctx)
      case ExprGt(a: Expr, b: Expr)   => typeEvalCompareOp(a, b, ctx)

      // add is overloaded, it is a special case
      case ExprAdd(a: Expr, b: Expr) => typeEvalAdd(a, b, ctx)

      // math operators on two arguments
      case ExprSub(a: Expr, b: Expr)    => typeEvalMathOp(a, b, ctx)
      case ExprMod(a: Expr, b: Expr)    => typeEvalMathOp(a, b, ctx)
      case ExprMul(a: Expr, b: Expr)    => typeEvalMathOp(a, b, ctx)
      case ExprDivide(a: Expr, b: Expr) => typeEvalMathOp(a, b, ctx)

      // Access an array element at [index]
      case ExprAt(array: Expr, index: Expr) =>
        val idxt = typeEval(index, ctx)
        if (idxt != TypeInt)
          throw new Exception(s"${index} must be an integer")
        val arrayt = typeEval(array, ctx)
        arrayt match {
          case TypeArray(elemType, _) => elemType
          case _                      => throw new Exception(s"subexpression ${array} in (${expr}) must be an array")
        }

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr) =>
        val condType = typeEval(cond, ctx)
        if (condType != TypeBoolean)
          throw new Exception(s"condition ${cond} must be a boolean")
        val tBranchT = typeEval(tBranch, ctx)
        val fBranchT = typeEval(fBranch, ctx)
        if (tBranchT != fBranchT)
          throw new Exception(
              s"The branches of conditional (${expr}) expression must the same type"
          )
        tBranchT

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case ExprApply(funcName: String, elements: Vector[Expr]) =>
        val elementTypes = elements.map(typeEval(_, ctx))
        stdlib.apply(funcName, elementTypes)

      // Access a field in a struct or an object. For example:
      //   Int z = x.a
      case ExprGetName(e: Expr, id: String) =>
        val et = typeEval(e, ctx)
        et match {
          case TypeStruct(name, members) =>
            members.get(id) match {
              case None =>
                throw new Exception(
                    s"Struct ${name} does not have member ${id} in expression ${expr}"
                )
              case Some(t) =>
                t
            }
        }
    }
  }

  // check the declaration and add a binding for its (variable -> wdlType)
  //
  // In a declaration the right hand type must be coercible to
  // the left hand type.
  //
  // Examples:
  //   Int x
  //   Int x = 5
  //   Int x = 7 + y
  def applyDecl(decl: Declaration, ctx: Context): Context = {
    decl.expr match {
      case None =>
        ()
      case Some(expr) =>
        val rhsType: Type = typeEval(expr, ctx)
        if (!isCoercibleTo(decl.wdlType, rhsType))
          throw new Exception(s"declaration ${decl} is badly typed")
    }
    ctx.get(decl.name) match {
      case None =>
        ctx + (decl.name -> decl.wdlType)
      case Some(_) =>
        throw new Exception(
            s"declaration ${decl} shadows an existing variable by the same name (${decl.name})"
        )
    }
  }

  // type check the input section and return a context with bindings for all of the input variables.
  private def applyInputSection(inputSection: InputSection, ctx: Context): Context = {
    val ctx2 = inputSection.declarations.foldLeft(ctx) {
      case (accu: Context, decl) =>
        applyDecl(decl, accu)
    }
    ctx2
  }

  private def applyOutputSection(outputSection: OutputSection, ctx: Context): Context = {
    outputSection.declarations.foldLeft(ctx) {
      case (accu: Context, decl) =>
        // check the declaration and add a binding for its (variable -> wdlType)
        applyDecl(decl, accu)
    }
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(
      inputSection: Option[InputSection],
      outputSection: Option[OutputSection]
  ): (Map[String, (Type, Boolean)], Map[String, Type]) = {

    val inputType: Map[String, (Type, Boolean)] = inputSection match {
      case None => Map.empty
      case Some(InputSection(decls)) =>
        decls.map {
          case Declaration(name, wdlType, Some(_)) =>
            // input has a default value, caller may omit it.
            name -> (wdlType, true)

          case Declaration(name, TypeOptional(wdlType), _) =>
            // input is optional, caller can omit it.
            name -> (TypeOptional(wdlType), true)

          case Declaration(name, wdlType, _) =>
            // input is compulsory
            name -> (wdlType, false)
        }.toMap
    }
    val outputType: Map[String, Type] = outputSection match {
      case None => Map.empty
      case Some(OutputSection(decls)) =>
        decls.map {
          case decl =>
            decl.name -> decl.wdlType
        }.toMap
    }
    (inputType, outputType)
  }

  // TASK
  //
  // - An inputs type has to match the type of its default value (if any)
  // - Check the declarations
  // - Assignments to an output variable must match
  //
  // We can't check the validity of the command section.
  private def applyTask(task: Task, ctxOuter: Context): Context = {
    val ctx: Context = task.input match {
      case None             => ctxOuter
      case Some(inpSection) => applyInputSection(inpSection, ctxOuter)
    }

    // TODO: type-check the runtime section

    // check the declaration, and accumulate context
    val ctx2 = task.declarations.foldLeft(ctx) {
      case (accu: Context, decl) =>
        applyDecl(decl, accu)
    }

    // check that all expressions in the command section are strings
    task.command.parts.foreach {
      case expr =>
        val t = typeEval(expr, ctx2)
        if (!isCoercibleTo(TypeString, t))
          throw new Exception(s"Expression ${expr} in the command section is coercible to a string")
    }

    // check the output section. We don't need the returned context.
    task.output.map(x => applyOutputSection(x, ctx2))

    // calculate the type signature of the task
    val (inputType, outputType) = calcSignature(task.input, task.output)
    val tt = TypeTask(task.name, inputType, outputType)
    ctxOuter.get(task.name) match {
      case None =>
        ctxOuter + (task.name -> tt)
      case Some(_) =>
        throw new Exception(s"Redeclaration of task ${task.name}")
    }
  }

  private def applyStruct(struct: TypeStruct, ctx: Context): Context = {
    ctx.get(struct.name) match {
      case None =>
        ctx + (struct.name -> struct)
      case Some(_) =>
        throw new Exception(s"Redeclaration of struct ${struct.name}")
    }
  }

  // 1. all the caller arguments have to exist with the correct types
  //    in the callee
  // 2. all the compulsory callee arguments must be specified. Optionals
  //    and arguments that have defaults can be skipped.
  private def applyCall(call: Call, ctx: Context): Context = {
    val callerInputs = call.inputs.map {
      case (name, expr) => name -> typeEval(expr, ctx)
    }.toMap

    val (calleeInputs, calleeOutputs) = ctx.get(call.name) match {
      case None =>
        throw new Exception(s"called task/workflow ${call.name} is not defined")
      case Some(TypeTask(_, input, output)) =>
        (input, output)
      case Some(TypeWorkflow(_, input, output)) =>
        (input, output)
      case _ =>
        throw new Exception(s"callee ${call.name} is not a task or workflow")
    }

    // type-check input arguments
    callerInputs.foreach {
      case (argName, wdlType) =>
        calleeInputs.get(argName) match {
          case None =>
            throw new Exception(
                s"call ${call} has argument ${argName} that does not exist in the callee"
            )
          case Some((calleeType, _)) =>
            if (!isCoercibleTo(calleeType, wdlType))
              throw new Exception(
                  s"argument ${argName} has wrong type ${wdlType}, expecting ${calleeType}"
              )
        }
    }

    // check that all the compulsory arguments are provided
    calleeInputs.foreach {
      case (argName, (wdlType, false)) =>
        callerInputs.get(argName) match {
          case None =>
            throw new Exception(
                s"compulsory argument ${argName} to task/workflow ${call.name} is missing"
            )
          case Some(_) => ()
        }
    }

    // build a type for the resulting object
    val callName = call.alias match {
      case None        => call.name
      case Some(alias) => alias
    }
    ctx + (callName -> TypeCall(callName, calleeOutputs))
  }

  private def applyWorkflow(wf: Workflow, ctxOuter: Context): Context = {
    val ctx: Context = wf.input match {
      case None             => ctxOuter
      case Some(inpSection) => applyInputSection(inpSection, ctxOuter)
    }

    // check the declaration, and accumulate context
    val ctxBody = wf.body.foldLeft(ctx) {
      case (accu: Context, decl: Declaration) =>
        applyDecl(decl, accu)
      case (accu, call: Call) =>
        applyCall(call, accu)

      // case (accu, scatter : Scatter) =>
      // case (accu, cond : Conditional) =>

      case (_, _) =>
        throw new Exception("Not implement yet")
    }

    // check the output section. We don't need the returned context.
    wf.output.map(x => applyOutputSection(x, ctxBody))

    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(wf.input, wf.output)
    val wft = TypeWorkflow(wf.name, inputType, outputType)
    ctxOuter.get(wf.name) match {
      case None =>
        ctxOuter + (wf.name -> wft)
      case Some(_) =>
        throw new Exception(s"Redeclaration of workflow ${wf.name}")
    }
  }

  // Main entry point
  //
  // check if the WDL document is correctly typed. Otherwise, throw an exception
  // describing the problem in a human readable fashion.
  def apply(doc: Document): Context = {
    val context: Context = doc.elements.foldLeft(Map.empty[String, Type]) {
      case (accu: Context, task: Task) =>
        applyTask(task, accu)

      case (accu, importDoc: ImportDoc) =>
        throw new Exception("imports not implemented yet")

      case (accu: Context, struct: TypeStruct) =>
        // Add the struct to the context
        applyStruct(struct, accu)

      case (_, other) =>
        throw new Exception("sanity: wrong element type in workflow")
    }

    // now that we have types for everything else, we can check the workflow
    doc.workflow match {
      case None     => context
      case Some(wf) => applyWorkflow(wf, context)
    }
  }
}
