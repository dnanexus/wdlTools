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
                   aliases: Map[String, T_Struct] = Map.empty,
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

  def bindInputSection(inputSection: Vector[TAT.InputDefinition]): Context = {
    // building bindings
    val bindings = inputSection.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap
    this.copy(inputs = bindings)
  }

  def bindOutputSection(oututSection: Vector[TAT.OutputDefinition]): Context = {
    // building bindings
    val bindings = oututSection.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap
    this.copy(outputs = bindings)
  }

  def bindVar(varName: String, wdlType: WdlType): Context = {
    declarations.get(varName) match {
      case None =>
        this.copy(declarations = declarations + (varName -> wdlType))
      case Some(_) =>
        throw new DuplicateDeclarationException(s"variable ${varName} shadows an existing variable")
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

  def bindStruct(s: T_Struct): Context = {
    aliases.get(s.name) match {
      case None =>
        this.copy(aliases = aliases + (s.name -> s))
      case Some(existingStruct: T_Struct) if s != existingStruct =>
        throw new DuplicateDeclarationException(s"struct ${s.name} is already declared")
      case Some(_: T_Struct) =>
        // The struct is defined a second time, with the exact same definition. Ignore.
        this
      case Some(_) =>
        throw new DuplicateDeclarationException(s"struct ${s.name} overrides an existing alias")
    }
  }

  // add a callable (task/workflow)
  def bindCallable(callable: T_Callable): Context = {
    callables.get(callable.name) match {
      case None =>
        this.copy(callables = callables + (callable.name -> callable))
      case Some(_) =>
        throw new DuplicateDeclarationException(
            s"a callable named ${callable.name} is already declared"
        )
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
                      aliases: Vector[AST.ImportAlias]): Context = {
    if (this.namespaces contains namespace) {
      throw new DuplicateDeclarationException(s"namespace ${namespace} already exists")
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
    val iAliasesTranslations: Map[String, String] = aliases.map {
      case AST.ImportAlias(src, dest, _) => src -> dest
    }.toMap
    val iAliases: Map[String, T_Struct] = iCtx.aliases.map {
      case (name, iStruct: T_Struct) =>
        iAliasesTranslations.get(name) match {
          case None          => name -> iStruct
          case Some(altName) => altName -> T_StructDef(altName, iStruct.members)
        }
      case (_, other) =>
        throw new RuntimeException(s"Expecting a struct but got ${other}")
    }

    // check that the imported structs do not step over existing definitions
    val doublyDefinedStructs =
      (this.aliases.keys.toSet intersect iAliases.keys.toSet).filter(sname =>
        this.aliases(sname) != iAliases(sname)
      )
    if (doublyDefinedStructs.nonEmpty) {
      throw new DuplicateDeclarationException(
          s"Struct(s) ${doublyDefinedStructs.mkString(",")} already defined in a different way"
      )
    } else {
      this.copy(aliases = this.aliases ++ iAliases,
                callables = callables ++ iCallables,
                namespaces = namespaces + namespace)
    }
  }
}
