package wdlTools.types

import java.net.URL
import wdlTools.syntax.{AbstractSyntax => AST, WdlVersion}
import wdlTools.syntax.TextSource
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
                   declarations: Map[String, WdlTypes.T] = Map.empty,
                   aliases: Map[String, T_Struct] = Map.empty,
                   callables: Map[String, T_Callable] = Map.empty,
                   namespaces: Set[String] = Set.empty) {
  type WdlType = WdlTypes.T

  def lookup(varName: String,
             bindings: Map[String, WdlType],
             srcText: TextSource): Option[WdlType] = {
    inputs.get(varName) match {
      case None    => ()
      case Some(t) => return Some(t)
    }
    declarations.get(varName) match {
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

  def bindVar(varName: String, wdlType: WdlType, srcText: TextSource): Context = {
    declarations.get(varName) match {
      case None =>
        this.copy(declarations = declarations + (varName -> wdlType))
      case Some(_) =>
        throw new TypeException(s"variable ${varName} shadows an existing variable",
                                srcText,
                                docSourceUrl)
    }
  }

  def bindStruct(s: T_Struct, srcText: TextSource): Context = {
    aliases.get(s.name) match {
      case None =>
        this.copy(aliases = aliases + (s.name -> s))
      case Some(existingStruct: T_Struct) =>
        if (s != existingStruct)
          throw new TypeException(s"struct ${s.name} is already declared", srcText, docSourceUrl)
        // The struct is defined a second time, with the exact same definition. Ignore.
        this
      case Some(other) =>
        throw new TypeException(s"struct ${s.name} overrides an existing alias",
                                srcText,
                                docSourceUrl)
    }
  }

  // add a callable (task/workflow)
  def bindCallable(callable: T_Callable, srcText: TextSource): Context = {
    callables.get(callable.name) match {
      case None =>
        this.copy(callables = callables + (callable.name -> callable))
      case Some(_) =>
        throw new TypeException(s"a callable named ${callable.name} is already declared",
                                srcText,
                                docSourceUrl)
    }
  }

  // add a bunch of bindings
  def bindVarList(bindings: Map[String, WdlType], srcText: TextSource): Context = {
    val existingVarNames = declarations.keys.toSet
    val newVarNames = bindings.keys.toSet
    val both = existingVarNames intersect newVarNames
    if (both.nonEmpty)
      throw new TypeException(s"Variables ${both} are being redeclared", srcText, docSourceUrl)
    this.copy(declarations = declarations ++ bindings)
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
                      aliases: Vector[AST.ImportAlias],
                      srcText: TextSource): Context = {
    if (this.namespaces contains namespace)
      throw new TypeException(s"namespace ${namespace} already exists", srcText, iCtx.docSourceUrl)

    // There cannot be any collisions because this is a new namespace
    val iCallables = iCtx.callables.map {
      case (name, taskSig: T_Task) =>
        val fqn = namespace + "." + name
        fqn -> taskSig.copy(name = fqn)
      case (name, wfSig: T_Workflow) =>
        val fqn = namespace + "." + name
        fqn -> wfSig.copy(name = fqn)
      case other =>
        throw new Exception(s"sanity: ${other.getClass}")
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
    val iAliases = iCtx.aliases.map {
      case (name, iStruct: T_Struct) =>
        iAliasesTranslations.get(name) match {
          case None          => name -> iStruct
          case Some(altName) => altName -> T_Struct(altName, iStruct.members)
        }
      case (name, other) =>
        throw new Exception(s"Expecting a struct but got ${other}")
    }.toMap

    // check that the imported structs do not step over existing definitions
    val doublyDefined = this.aliases.keys.toSet intersect iAliases.keys.toSet
    for (sname <- doublyDefined) {
      if (this.aliases(sname) != iAliases(sname))
        throw new TypeException(s"Type ${sname} is already defined in a different way",
                                srcText,
                                iCtx.docSourceUrl)
    }

    this.copy(aliases = this.aliases ++ iAliases,
              callables = callables ++ iCallables,
              namespaces = namespaces + namespace)
  }
}
