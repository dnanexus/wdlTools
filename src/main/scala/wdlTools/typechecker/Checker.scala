package wdlTools.typechecker

import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.TextSource
import WdlTypes._

case class Checker(stdlib: Stdlib) {

  // A group of bindings. This is typically a part of the context. For example,
  // the body of a scatter.
  type Bindings = Map[String, WT]


  // An entire context
  //
  // There separate namespaces for variables, struct definitions, and callables (tasks/workflows)
  case class Context(declarations: Map[String, WT],
                     structs: Map[String, WT_Struct],
                     callables: Map[String, WT] /* tasks and workflows */ ) {
    def bind(varName: String,
             wdlType: WT,
             srcText : TextSource): Context = {
      declarations.get(varName) match {
        case None =>
          this.copy(declarations = declarations + (varName -> wdlType))
        case Some(_) =>
          throw new TypeException(s"variable ${varName} shadows an existing variable", srcText)
      }
    }

/*    def bind(decl: Declaration): Context = {
      declarations.get(decl.name) match {
        case None =>
          this.copy(declarations = declarations + (decl.name -> decl.wdlType))
        case Some(_) =>
          throw new Exception(
              s"declaration ${decl} shadows an existing variable by the same name (${decl.name})"
          )
      }
    }*/

    def bind(s: WT_Struct, srcText : TextSource): Context = {
      structs.get(s.name) match {
        case None =>
          this.copy(structs = structs + (s.name -> s))
        case Some(_) =>
          throw new TypeException(s"struct ${s.name} is already declared", srcText)
      }
    }

    def bind(taskSig: WT_Task, srcText : TextSource): Context = {
      callables.get(taskSig.name) match {
        case None =>
          this.copy(callables = callables + (taskSig.name -> taskSig))
        case Some(_) =>
          throw new TypeException(s"a callable named ${taskSig.name} is already declared", srcText)
      }
    }

    def bind(wfSig: WT_Workflow, srcText : TextSource): Context = {
      callables.get(wfSig.name) match {
        case None =>
          this.copy(callables = callables + (wfSig.name -> wfSig))
        case Some(_) =>
          throw new TypeException(s"a callable named ${wfSig.name} is already declared", srcText)
      }
    }

    def bind(bindings: Bindings, srcText : TextSource): Context = {
      val existingVarNames = declarations.keys.toSet
      val newVarNames = bindings.keys.toSet
      val both = existingVarNames intersect newVarNames
      if (both.nonEmpty)
        throw new TypeException(s"Variables ${both} are being redeclared", srcText)
      this.copy(declarations = declarations ++ bindings)
    }
  }

  private val contextEmpty = Context(Map.empty, Map.empty, Map.empty)


  private def typeEvalMathOp(expr: Expr, ctx: Context): WT = {
    val t = typeEval(expr, ctx)
    t match {
      case _ : TypeInt   => WT_Int
      case _ : TypeFloat => WT_Float
      case _         => throw new TypeException(s"${expr} must be an integer or a float", expr.text)
    }
  }

  // The add operation is overloaded.
  // 1) The result of adding two integers is an integer
  // 2)    -"-                   floats   -"-   float
  // 3)    -"-                   strings  -"-   string
  private def typeEvalAdd(a: Expr, b: Expr, ctx: Context): WT = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (WT_Int, WT_Int)     => WT_Int
      case (WT_Float, WT_Int)   => WT_Float
      case (WT_Int, WT_Float)   => WT_Float
      case (WT_Float, WT_Float) => WT_Float

      // if we are adding strings, the result is a string
      case (WT_String, WT_String) => WT_String
      case (WT_String, WT_Int)    => WT_String
      case (WT_String, WT_Float)  => WT_String
      case (WT_Int, WT_String)    => WT_String
      case (WT_Float, WT_String)  => WT_String

      // adding files is equivalent to concatenating paths
      case (WT_File, WT_File) => WT_File

      case (_, _) => throw new TypeException(s"Expressions ${a} and ${b} cannot be added", a.text)
    }
  }

  private def typeEvalMathOp(a: Expr, b: Expr, ctx: Context): WT = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (WT_Int, WT_Int)     => WT_Int
      case (WT_Float, WT_Int)   => WT_Float
      case (WT_Int, WT_Float)   => WT_Float
      case (WT_Float, WT_Float) => WT_Float
      case (_, _)                 =>
        throw new TypeException(s"Expressions ${a} and ${b} must be integers or floats", a.text)
    }
  }

  private def typeEvalLogicalOp(expr: Expr, ctx: Context): WT = {
    val t = typeEval(expr, ctx)
    t match {
      case WT_Boolean => WT_Boolean
      case other =>
        throw new TypeException(s"${expr} must be a boolean, it is ${other}", expr.text)
    }
  }

  private def typeEvalLogicalOp(a: Expr, b: Expr, ctx: Context): WT = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    (at, bt) match {
      case (WT_Boolean, WT_Boolean) => WT_Boolean
      case (_, _)                     =>
        throw new TypeException(s"${a} and ${b} must have boolean type", a.text)
    }
  }

  private def typeEvalCompareOp(a: Expr, b: Expr, ctx: Context): WT = {
    val at = typeEval(a, ctx)
    val bt = typeEval(b, ctx)
    if (at == bt) {
      // These could be complex types, such as Array[Array[Int]].
      return WT_Boolean
    }

    // Even if the types are not the same, there are cases where they can
    // be compared.
    (at, bt) match {
      case (WT_Int, WT_Float) => WT_Boolean
      case (WT_Float, WT_Int) => WT_Boolean
      case (_, _) =>
        throw new TypeException(s"Expressions ${a} and ${b} must have the same type", a.text)
    }
  }

  private def typeTranslate(t: Type): WT = {
    t match {
      case TypeOptional(t, _) => WT_Optional(typeTranslate(t))
      case TypeArray(t, _, _) => WT_Array(typeTranslate(t))
      case TypeMap(k, v, _) => WT_Map(typeTranslate(k), typeTranslate(v))
      case TypePair(l, r, _) => WT_Pair(typeTranslate(l), typeTranslate(r))
      case _ : TypeString => WT_String
      case _ : TypeFile => WT_File
      case _ : TypeBoolean => WT_Boolean
      case _ : TypeInt => WT_Int
      case _ : TypeFloat => WT_Float
      case TypeIdentifier(id, _) => WT_Identifier(id)
      case _ : TypeObject => WT_Object
      case TypeStruct(name, members, _) =>
        WT_Struct(name,
                  members.map{ case (name, t2) => name -> typeTranslate(t2) })
    }
  }

  // Figure out what the type of an expression is.
  //
  private def typeEval(expr: Expr, ctx: Context): WT = {
    expr match {
      // base cases, primitive types
      case _: ValueString  => WT_String
      case _: ValueFile    => WT_File
      case _: ValueBoolean => WT_Boolean
      case _: ValueInt     => WT_Int
      case _: ValueFloat   => WT_Float

      // an identifier has to be bound to a known type
      case ExprIdentifier(id, _) =>
        (ctx.declarations.get(id), ctx.structs.get(id)) match {
          case (None, None)    => throw new RuntimeException(s"Identifier ${id} is not defined")
          case (Some(t), None) => t
          case (None, Some(t)) => t
          case (Some(_), Some(_)) =>
            throw new TypeException(s"sanity: ${id} is both a struct and an identifier", expr.text)
        }

      // All the sub-exressions have to be strings, or coercible to strings
      case ExprCompoundString(vec, _) =>
        vec foreach { subExpr =>
          val t = typeEval(subExpr, ctx)
          if (!isCoercibleTo(WT_String, t))
            throw new TypeException(s"WT_ ${t} is not coercible to string", expr.text)
        }
        WT_String

      case ExprPair(l, r, _)                => WT_Pair(typeEval(l, ctx), typeEval(r, ctx))
      case ExprArray(vec, _) if vec.isEmpty =>
        // The array is empty, we can't tell what the array type is.
        WT_Array(WT_Unknown)

      case ExprArray(vec, _) =>
        val vecTypes = vec.map(typeEval(_, ctx))
        val t = vecTypes.head
        if (!vecTypes.tail.forall(isCoercibleTo(_, t)))
          throw new TypeException(s"Array elements do not all have type ${t}", expr.text)
        WT_Array(t)

      case ExprMap(m, _) if m.isEmpty =>
        // The map type is unknown
        WT_Map(WT_Unknown, WT_Unknown)

      case ExprMap(m, _) =>
        // figure out the types from the first element
        val mTypes: Map[WT, WT] = m.map {
          case (k, v) => typeEval(k, ctx) -> typeEval(v, ctx)
        }
        val tk = mTypes.keys.head
        if (!mTypes.keys.tail.forall(isCoercibleTo(_, tk)))
          throw new TypeException(s"Map keys do not all have type ${tk}", expr.text)
        val tv = mTypes.values.head
        if (!mTypes.values.tail.forall(isCoercibleTo(_, tv)))
          throw new TypeException(s"Map values do not all have type ${tv}", expr.text)
        WT_Map(tk, tv)

      // These are expressions like:
      // ${true="--yes" false="--no" boolean_value}
      case ExprPlaceholderEqual(t: Expr, f: Expr, value: Expr, _) =>
        val tType = typeEval(t, ctx)
        val fType = typeEval(f, ctx)
        if (fType != tType)
          throw new TypeException(s"subexpressions ${t} and ${f} in ${expr} must have the same type", expr.text)
        val tv = typeEval(value, ctx)
        if (tv != WT_Boolean)
          throw new TypeException(
              s"${value} in ${expr} should have boolean type, it has type ${tv} instead", expr.text
          )
        tType

      // An expression like:
      // ${default="foo" optional_value}
      case ExprPlaceholderDefault(default: Expr, value: Expr, _) =>
        val vt = typeEval(value, ctx)
        val dt = typeEval(default, ctx)
        vt match {
          case WT_Optional(vt2) if vt2 == dt => dt
          case _ =>
            throw new TypeException(s"""|Subxpression ${value} in ${expr} must have type optional(${dt})
                                        |it has type ${vt} instead""".stripMargin.replaceAll("\n", " "),
                                    expr.text)
        }

      // An expression like:
      // ${sep=", " array_value}
      case ExprPlaceholderSep(sep: Expr, value: Expr, _) =>
        val sepType = typeEval(sep, ctx)
        if (sepType != WT_String)
          throw new TypeException(s"separator ${sep} in ${expr} must have string type", expr.text)
        val vt = typeEval(value, ctx)
        vt match {
          case WT_Array(t) if isCoercibleTo(WT_String, t) =>
            WT_String
          case other =>
            throw new TypeException(
                s"expression ${value} should be of type Array[String], but it is ${other}", expr.text
            )
        }

      // math operators on one argument
      case ExprUniraryPlus(value, _)  => typeEvalMathOp(value, ctx)
      case ExprUniraryMinus(value, _) => typeEvalMathOp(value, ctx)

      // logical operators
      case ExprLor(a: Expr, b: Expr, _)  => typeEvalLogicalOp(a, b, ctx)
      case ExprLand(a: Expr, b: Expr, _) => typeEvalLogicalOp(a, b, ctx)
      case ExprNegate(value: Expr, _)    => typeEvalLogicalOp(value, ctx)

      // equality comparisons
      case ExprEqeq(a: Expr, b: Expr, _) => typeEvalCompareOp(a, b, ctx)
      case ExprNeq(a: Expr, b: Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case ExprLt(a: Expr, b: Expr, _)   => typeEvalCompareOp(a, b, ctx)
      case ExprGte(a: Expr, b: Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case ExprLte(a: Expr, b: Expr, _)  => typeEvalCompareOp(a, b, ctx)
      case ExprGt(a: Expr, b: Expr, _)   => typeEvalCompareOp(a, b, ctx)

      // add is overloaded, it is a special case
      case ExprAdd(a: Expr, b: Expr, _) => typeEvalAdd(a, b, ctx)

      // math operators on two arguments
      case ExprSub(a: Expr, b: Expr, _)    => typeEvalMathOp(a, b, ctx)
      case ExprMod(a: Expr, b: Expr, _)    => typeEvalMathOp(a, b, ctx)
      case ExprMul(a: Expr, b: Expr, _)    => typeEvalMathOp(a, b, ctx)
      case ExprDivide(a: Expr, b: Expr, _) => typeEvalMathOp(a, b, ctx)

      // Access an array element at [index]
      case ExprAt(array: Expr, index: Expr, _) =>
        val idxt = typeEval(index, ctx)
        if (idxt != WT_Int)
          throw new TypeException(s"${index} must be an integer", expr.text)
        val arrayt = typeEval(array, ctx)
        arrayt match {
          case WT_Array(elemType) => elemType
          case _                      =>
            throw new TypeException(s"subexpression ${array} in (${expr}) must be an array", expr.text)
        }

      // conditional:
      // if (x == 1) then "Sunday" else "Weekday"
      case ExprIfThenElse(cond: Expr, tBranch: Expr, fBranch: Expr, _) =>
        val condType = typeEval(cond, ctx)
        if (condType != WT_Boolean)
          throw new TypeException(s"condition ${cond} must be a boolean", expr.text)
        val tBranchT = typeEval(tBranch, ctx)
        val fBranchT = typeEval(fBranch, ctx)
        if (tBranchT != fBranchT)
          throw new TypeException(
              s"The branches of conditional (${expr}) expression must the same type", expr.text
          )
        tBranchT

      // Apply a standard library function to arguments. For example:
      //   read_int("4")
      case ExprApply(funcName: String, elements: Vector[Expr], _) =>
        val elementTypes = elements.map(typeEval(_, ctx))
        stdlib.apply(funcName, elementTypes, expr)

      // Access a field in a struct or an object. For example:
      //   Int z = x.a
      case ExprGetName(e: Expr, id: String, _) =>
        val et = typeEval(e, ctx)
        et match {
          case WT_Struct(name, members) =>
            members.get(id) match {
              case None =>
                throw new TypeException(
                    s"Struct ${name} does not have member ${id} in expression ${expr}", expr.text
                )
              case Some(t) =>
                t
            }
          case WT_Call(name, members) =>
            members.get(id) match {
              case None =>
                throw new TypeException(
                    s"Call object ${name} does not have member ${id} in expression ${expr}", expr.text
                )
              case Some(t) =>
                t
            }
          case _ =>
            throw new TypeException(s"Member access ${id} in expression ${e} is illegal", expr.text)
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
  private def applyDecl(decl: Declaration, ctx: Context): (String, WT) = {
    val lhsType: WT = typeTranslate(decl.wdlType)
    decl.expr match {
      case None =>
        ()
      case Some(expr) =>
        val rhsType: WT = typeEval(expr, ctx)
        if (!isCoercibleTo(lhsType, rhsType))
          throw new TypeException(s"declaration ${decl} is badly typed", decl.text)
    }
    (decl.name, lhsType)
  }

  // type check the input section and return bindings for all of the input variables.
  private def applyInputSection(inputSection: InputSection, ctx: Context): Bindings = {
    inputSection.declarations.foldLeft(Map.empty[String, WT]) {
      case (accu, decl) =>
        val (varName, typ) = applyDecl(decl, ctx.bind(accu, inputSection.text))
        accu + (varName -> typ)
    }
  }

  // type check the input section and return bindings for all of the output variables.
  private def applyOutputSection(outputSection: OutputSection, ctx: Context): Bindings = {
    outputSection.declarations.foldLeft(Map.empty[String, WT]) {
      case (accu, decl) =>
        // check the declaration and add a binding for its (variable -> wdlType)
        val (varName, typ) = applyDecl(decl, ctx.bind(accu, outputSection.text))
        accu + (varName -> typ)
    }
  }

  // calculate the type signature of a workflow or a task
  private def calcSignature(
      inputSection: Option[InputSection],
      outputSection: Option[OutputSection]
  ): (Map[String, (WT, Boolean)], Map[String, WT]) = {

    val inputType: Map[String, (WT, Boolean)] = inputSection match {
      case None => Map.empty
      case Some(InputSection(decls, _)) =>
        decls.map {
          case Declaration(name, wdlType, Some(_), _) =>
            // input has a default value, caller may omit it.
            name -> (typeTranslate(wdlType), true)

          case Declaration(name, TypeOptional(wdlType, _), _, _) =>
            // input is optional, caller can omit it.
            name -> (WT_Optional(typeTranslate(wdlType)), true)

          case Declaration(name, wdlType, _, _) =>
            // input is compulsory
            name -> (typeTranslate(wdlType), false)
        }.toMap
    }
    val outputType: Map[String, WT] = outputSection match {
      case None => Map.empty
      case Some(OutputSection(decls, _)) =>
        decls.map(decl => decl.name -> typeTranslate(decl.wdlType)).toMap
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
  private def applyTask(task: Task, ctxOuter: Context): WT_Task = {
    val ctx: Context = task.input match {
      case None => ctxOuter
      case Some(inpSection) =>
        val bindings = applyInputSection(inpSection, ctxOuter)
        ctxOuter.bind(bindings, task.text)
    }

    // TODO: type-check the runtime section

    // check the declarations, and accumulate context
    val ctxDecl = task.declarations.foldLeft(ctx) {
      case (accu: Context, decl) =>
        val (varName, typ) = applyDecl(decl, accu)
        accu.bind(varName, typ, decl.text)
    }

    // check that all expressions in the command section are strings
    task.command.parts.foreach { expr =>
      val t = typeEval(expr, ctxDecl)
      if (!isCoercibleTo(WT_String, t))
        throw new TypeException(
          s"Expression ${expr} in the command section is coercible to a string",
          expr.text)
    }

    // check the output section. We don't need the returned context.
    task.output.map(x => applyOutputSection(x, ctxDecl))

    // calculate the type signature of the task
    val (inputType, outputType) = calcSignature(task.input, task.output)
    WT_Task(task.name, inputType, outputType)
  }

  // 1. all the caller arguments have to exist with the correct types
  //    in the callee
  // 2. all the compulsory callee arguments must be specified. Optionals
  //    and arguments that have defaults can be skipped.
  private def applyCall(call: Call, ctx: Context): (String, WT_Call) = {
    val callerInputs = call.inputs.map {
      case (name, expr) => name -> typeEval(expr, ctx)
    }

    val (calleeInputs, calleeOutputs) = ctx.callables.get(call.name) match {
      case None =>
        throw new TypeException(s"called task/workflow ${call.name} is not defined", call.text)
      case Some(WT_Task(_, input, output)) =>
        (input, output)
      case Some(WT_Workflow(_, input, output)) =>
        (input, output)
      case _ =>
        throw new TypeException(s"callee ${call.name} is not a task or workflow", call.text)
    }

    // type-check input arguments
    callerInputs.foreach {
      case (argName, wdlType) =>
        calleeInputs.get(argName) match {
          case None =>
            throw new TypeException(
              s"call ${call} has argument ${argName} that does not exist in the callee", call.text
            )
          case Some((calleeType, _)) =>
            if (!isCoercibleTo(calleeType, wdlType))
              throw new TypeException(
                s"argument ${argName} has wrong type ${wdlType}, expecting ${calleeType}",
                call.text
              )
        }
    }

    // check that all the compulsory arguments are provided
    calleeInputs.foreach {
      case (argName, (_, false)) =>
        callerInputs.get(argName) match {
          case None =>
            throw new TypeException(
                s"compulsory argument ${argName} to task/workflow ${call.name} is missing", call.text
            )
          case Some(_) => ()
        }
    }

    // build a type for the resulting object
    val callName = call.alias match {
      case None        => call.name
      case Some(alias) => alias
    }

    if (ctx.declarations contains callName)
      throw new TypeException(s"call ${callName} shadows an existing definition", call.text)
    (callName -> WT_Call(callName, calleeOutputs))
  }

  // The body of the scatter becomes accessible to statements that come after it.
  // The iterator is not visible outside the scatter body.
  //
  // for (i in [1, 2, 3]) {
  //    call A
  // }
  //
  // Variable "i" is not visible after the scatter completes.
  // A's members are arrays.
  private def applyScatter(scatter: Scatter, ctxOuter: Context): Bindings = {
    val collectionType = typeEval(scatter.expr, ctxOuter)
    val elementType = collectionType match {
      case WT_Array(elementType) => elementType
      case _ =>
        throw new Exception(s"Collection in scatter (${scatter}) is not an array type")
    }
    // add a binding for the iteration variable
    val ctxInner = ctxOuter.bind(scatter.identifier, elementType, scatter.text)

    // Add an array type to all variables defined in the scatter body
    val bodyBindings: Bindings = scatter.body.foldLeft(Map.empty[String, WT]) {
      case (accu: Bindings, decl: Declaration) =>
        val (varName, typ) = applyDecl(decl, ctxInner.bind(accu, decl.text))
        accu + (varName -> WT_Array(typ))

      case (accu: Bindings, call: Call) =>
        val (callName, callType) = applyCall(call, ctxInner.bind(accu, call.text))
        val callOutput = callType.output.map {
          case (name, t) => name -> WT_Array(t)
        }.toMap
        accu + (callName -> WT_Call(callType.name, callOutput))

      case (accu: Bindings, subSct: Scatter) =>
        // a nested scatter
        val sctBindings = applyScatter(subSct, ctxInner.bind(accu, subSct.text))
        val sctBindings2 = sctBindings.map {
          case (varName, typ) => varName -> WT_Array(typ)
        }.toMap
        accu ++ sctBindings2

      case (accu: Bindings, cond: Conditional) =>
        // a nested conditional
        val condBindings = applyConditional(cond, ctxInner.bind(accu, cond.text))
        val condBindings2 = condBindings.map {
          case (varName, typ) => varName -> WT_Array(typ)
        }.toMap
        accu ++ condBindings2

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }
    // The iterator identifier is not exported outside the scatter
    bodyBindings
  }

  // The body of a conditional is accessible to the statements that come after it.
  // This is different than the scoping rules for other programming languages.
  //
  // Add an optional modifier to all the types inside the body.
  private def applyConditional(cond: Conditional, ctxOuter: Context): Bindings = {
    val condType = typeEval(cond.expr, ctxOuter)
    if (condType != WT_Boolean)
      throw new Exception(s"Expression ${cond.expr} must have boolean type")

    // Add an array type to all variables defined in the scatter body
    val bodyBindings = cond.body.foldLeft(Map.empty[String, WT]) {
      case (accu: Bindings, decl: Declaration) =>
        val (varName, typ) = applyDecl(decl, ctxOuter.bind(accu, decl.text))
        accu + (varName -> WT_Optional(typ))

      case (accu: Bindings, call: Call) =>
        val (callName, callType) = applyCall(call, ctxOuter.bind(accu, call.text))
        val callOutput = callType.output.map {
          case (name, t) => name -> WT_Optional(t)
        }.toMap
        accu + (callName -> WT_Call(callType.name, callOutput))

      case (accu: Bindings, subSct: Scatter) =>
        // a nested scatter
        val sctBindings = applyScatter(subSct, ctxOuter.bind(accu, subSct.text))
        val sctBindings2 = sctBindings.map {
          case (varName, typ) => varName -> WT_Optional(typ)
        }.toMap
        accu ++ sctBindings2

      case (accu: Bindings, cond: Conditional) =>
        // a nested conditional
        val condBindings = applyConditional(cond, ctxOuter.bind(accu, cond.text))
        val condBindings2 = condBindings.map {
          case (varName, typ) => varName -> WT_Optional(typ)
        }.toMap
        accu ++ condBindings2

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }

    bodyBindings
  }

  private def applyWorkflow(wf: Workflow, ctxOuter: Context): Context = {
    val ctx: Context = wf.input match {
      case None => ctxOuter
      case Some(inpSection) =>
        val inputs = applyInputSection(inpSection, ctxOuter)
        ctxOuter.bind(inputs, inpSection.text)
    }

    val ctxBody = wf.body.foldLeft(ctx) {
      case (accu: Context, decl: Declaration) =>
        val (name, typ) = applyDecl(decl, accu)
        accu.bind(name, typ, decl.text)

      case (accu: Context, call: Call) =>
        val (callName, callType) = applyCall(call, accu)
        accu.bind(callName, callType, call.text)

      case (accu: Context, scatter: Scatter) =>
        val sctBindings = applyScatter(scatter, accu)
        accu.bind(sctBindings, scatter.text)

      case (accu: Context, cond: Conditional) =>
        val condBindings = applyConditional(cond, accu)
        accu.bind(condBindings, cond.text)

      case (_, other) =>
        throw new Exception(s"Sanity: ${other}")
    }

    // check the output section. We don't need the returned context.
    wf.output.map(x => applyOutputSection(x, ctxBody))

    // calculate the type signature of the workflow
    val (inputType, outputType) = calcSignature(wf.input, wf.output)
    val wfSignature = WT_Workflow(wf.name, inputType, outputType)
    ctxOuter.bind(wf.name, wfSignature, wf.text)
  }

  // Main entry point
  //
  // check if the WDL document is correctly typed. Otherwise, throw an exception
  // describing the problem in a human readable fashion.
  def apply(doc: Document, ctxOuter: Context = contextEmpty): Context = {
    val context: Context = doc.elements.foldLeft(ctxOuter) {
      case (accu: Context, task: Task) =>
        val tt = applyTask(task, accu)
        accu.bind(tt, task.text)

      case (_, _: ImportDoc) =>
        throw new Exception("imports not implemented yet")

      case (accu: Context, struct: TypeStruct) =>
        // Add the struct to the context
        accu.bind(typeTranslate(struct).asInstanceOf[WT_Struct], struct.text)

      case (_, other) =>
        throw new Exception(s"sanity: wrong element type in workflow $other")
    }

    // now that we have types for everything else, we can check the workflow
    doc.workflow match {
      case None     => context
      case Some(wf) => applyWorkflow(wf, context)
    }
  }
}
