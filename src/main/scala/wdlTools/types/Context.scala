package wdlTools.types

import wdlTools.syntax.{WdlVersion, AbstractSyntax => AST}
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.types.WdlTypes._
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.util.{FileSource, Logger}

/**
  * Type inference context.
  * @param version WDL version of document being inferred
  * @param stdlib Standard function library
  * @param docSource WDL document source
  * @param inputs Inputs namespace
  * @param outputs Outputs namespace
  * @param declarations Declarations namespace
  * @param aliases Type alias namespace
  * @param callables Callables namespace
  * @param namespaces Set of all namespaces in the document tree
  */
case class Context(
    version: WdlVersion,
    stdlib: Stdlib,
    docSource: FileSource,
    inputs: Map[String, WdlTypes.T] = Map.empty,
    outputs: Map[String, WdlTypes.T] = Map.empty,
    declarations: Map[String, WdlTypes.T] = Map.empty,
    aliases: Map[String, T_Struct] = Map.empty,
    callables: Map[String, T_Callable] = Map.empty,
    namespaces: Set[String] = Set.empty
) {
  type WdlType = WdlTypes.T
  type Bindings = Map[String, WdlType]

  def lookup(varName: String, bindings: Bindings): Option[WdlType] = {
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

  def bindDeclaration(name: String, wdlType: WdlType): Context = {
    declarations.get(name) match {
      case None =>
        this.copy(declarations = declarations + (name -> wdlType))
      case Some(_) =>
        throw new DuplicateDeclarationException(s"variable ${name} shadows an existing variable")
    }
  }

  /**
    * Merge current declaration bindings.
    * @return
    */
  def bindDeclarations(bindings: Bindings): Context = {
    val both = declarations.keySet.intersect(bindings.keySet)
    if (both.nonEmpty) {
      throw new DuplicateDeclarationException(
          s"Trying to bind variables that have already been declared: ${both.mkString(", ")}"
      )
    }
    this
      .copy(declarations = declarations ++ bindings)
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
      case (name, taskSig: T_Task) =>
        val fqn = namespace + "." + name
        fqn -> taskSig.copy(name = fqn)
      case (name, wfSig: T_Workflow) =>
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
          case Some(altName) => altName -> T_Struct(altName, iStruct.members)
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

object Context {
  def create(doc: AST.Document, regime: TypeCheckingRegime, logger: Logger): Context = {
    val wdlVersion = doc.version.value
    Context(
        version = wdlVersion,
        stdlib = Stdlib(regime, wdlVersion, logger),
        docSource = doc.source
    )
  }
}
