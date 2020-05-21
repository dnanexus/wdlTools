package wdlTools.types

import java.net.URL
import wdlTools.syntax.{AbstractSyntax => AST, WdlVersion}
import wdlTools.types.WdlTypes._
import wdlTools.types.{TypedAbstractSyntax => TAT}

// An entire context
//
// There are separate namespaces for variables, struct definitions, and callables (tasks/workflows).
// An additional variable holds a list of all imported namespaces.
case class Context(version: WdlVersion,
                   stdlib: Stdlib,
                   docSourceUrl: Option[URL] = None,
                   inputs: Map[String, WdlTypes.T] = Map.empty,
                   outputs: Map[String, WdlTypes.T] = Map.empty,
                   declarations: Map[String, WdlTypes.T] = Map.empty,
                   structs: Map[String, T_Struct] = Map.empty,
                   callables: Map[String, T_Callable] = Map.empty,
                   namespaces: Set[String] = Set.empty) {
  type WdlType = WdlTypes.T

  def lookup(varName: String, bindings: Map[String, WdlType]): Option[WdlType] = {
    inputs.get(varName) match {
      case None    => ()
      case Some(t) => return Some(t)
    }
    declarations.get(varName) match {
      case None    => ()
      case Some(t) => return Some(t)
    }
    outputs.get(varName) match {
      case None    => ()
      case Some(t) => return Some(t)
    }
    bindings.get(varName) match {
      case None    => ()
      case Some(t) => return Some(t)
    }
    None
  }

  def bindInputSection(inputSection: TAT.InputSection): Context = {
    // building bindings
    val bindings = inputSection.declarations.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap
    this.copy(inputs = bindings)
  }

  def bindOutputSection(oututSection: TAT.OutputSection): Context = {
    // building bindings
    val bindings = oututSection.declarations.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap
    this.copy(outputs = bindings)
  }

  def bindVar(varName: String, wdlType: WdlType): Either[Context, String] = {
    declarations.get(varName) match {
      case None =>
        Left(this.copy(declarations = declarations + (varName -> wdlType)))
      case Some(_) =>
        Right(s"variable ${varName} shadows an existing variable")
    }
  }

  // add a bunch of bindings
  // the caller is responsible for ensuring that each binding is either
  // not a duplicate of an existing variables or that its type extends Invalid
  def bindVarList(bindings: Map[String, WdlType]): Context = {
    val both = declarations.keys.toSet intersect bindings.keys.toSet
    val keep = bindings.filter {
      case (name, _: Invalid) if both.contains(name) =>
        // ignore duplicate var if it is Invalid
        false
      case (name, _) if both.contains(name) =>
        throw new RuntimeException(
            "Trying to bind variables that have already been declared with non-error types"
        )
      case _ => true
    }
    this.copy(declarations = declarations ++ keep)
  }

  def bindStruct(s: T_Struct): Either[Context, String] = {
    structs.get(s.name) match {
      case None =>
        Left(this.copy(structs = structs + (s.name -> s)))
      case Some(existingStruct: T_Struct) if s != existingStruct =>
        Right(s"struct ${s.name} is already declared")
      case _ =>
        // The struct is defined a second time, with the exact same definition. Ignore.
        Left(this)
    }
  }

  // add a callable (task/workflow)
  def bindCallable(callable: T_Callable): Either[Context, String] = {
    callables.get(callable.name) match {
      case None =>
        Left(this.copy(callables = callables + (callable.name -> callable)))
      case Some(_) =>
        Right(s"a callable named ${callable.name} is already declared")
    }
  }

  // When we import another document all of its definitions are prefixed with the
  // namespace name.
  //
  // -- library.wdl --
  // task add {}
  // workflow act {}
  //
  // import "library.wdl" as lib
  // workflow hello {
  //    call lib.add
  //    call lib.act
  // }
  def bindImportedDoc(namespace: String,
                      iCtx: Context,
                      aliases: Vector[AST.ImportAlias]): Either[Context, String] = {
    if (this.namespaces contains namespace) {
      return Right(s"namespace ${namespace} already exists")
    }

    // There cannot be any collisions because this is a new namespace
    val iCallables = iCtx.callables.map {
      case (name, taskSig: T_TaskDef) =>
        val fqn = namespace + "." + name
        fqn -> taskSig.copy(name = fqn)
      case (name, wfSig: T_WorkflowDef) =>
        val fqn = namespace + "." + name
        fqn -> wfSig.copy(name = fqn)
      case other =>
        throw new RuntimeException(s"sanity: ${other.getClass}")
    }

    // rename the imported structs according to the aliases
    //
    // import http://example.com/another_exampl.wdl as ex2
    //     alias Parent as Parent2
    //     alias Child as Child2
    //     alias GrandChild as GrandChild2
    //
    val aliasesMap: Map[String, String] = aliases.map {
      case AST.ImportAlias(src, dest, _) => src -> dest
    }.toMap
    val iStructs = iCtx.structs.map {
      case (name, iStruct) =>
        aliasesMap.get(name) match {
          case None          => name -> iStruct
          case Some(altName) => altName -> T_StructDef(altName, iStruct.members)
        }
    }

    // check that the imported structs do not step over existing definitions
    val doublyDefinedStructs = this.structs.keys.toSet intersect iStructs.keys.toSet.filter(sname =>
      this.structs(sname) != iStructs(sname)
    )
    if (doublyDefinedStructs.nonEmpty) {
      Right(s"Struct(s) ${doublyDefinedStructs.mkString(",")} already defined in a different way")
    } else {
      Left(
          this.copy(structs = structs ++ iStructs,
                    callables = callables ++ iCallables,
                    namespaces = namespaces + namespace)
      )
    }
  }
}
