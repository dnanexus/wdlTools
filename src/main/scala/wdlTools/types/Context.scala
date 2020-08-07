package wdlTools.types

import wdlTools.syntax.{WdlVersion, AbstractSyntax => AST}
import wdlTools.types.TypeCheckingRegime.TypeCheckingRegime
import wdlTools.types.WdlTypes._
import wdlTools.types.{TypedAbstractSyntax => TAT}
import wdlTools.util.{Bindings, DuplicateBindingException, FileSource, Logger}

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
    inputs: Bindings[WdlTypes.T] = Bindings[WdlTypes.T](elementType = "input"),
    outputs: Bindings[WdlTypes.T] = Bindings[WdlTypes.T](elementType = "output"),
    declarations: Bindings[WdlTypes.T] = Bindings[WdlTypes.T](elementType = "declaration"),
    aliases: Bindings[T_Struct] = Bindings[WdlTypes.T_Struct](elementType = "struct"),
    callables: Bindings[T_Callable] = Bindings[WdlTypes.T_Callable](elementType = "callable"),
    namespaces: Set[String] = Set.empty
) {
  type WdlType = WdlTypes.T

  def lookup(varName: String, bindings: Bindings[WdlType]): Option[WdlType] = {
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
    this.copy(inputs = inputs.update(bindings))
  }

  def bindOutputSection(oututSection: Vector[TAT.OutputDefinition]): Context = {
    // building bindings
    val bindings = oututSection.map { tDecl =>
      tDecl.name -> tDecl.wdlType
    }.toMap
    this.copy(outputs = outputs.update(bindings))
  }

  def bindDeclaration(name: String, wdlType: WdlType): Context = {
    this.copy(declarations = declarations.add(name, wdlType))
  }

  /**
    * Merge current declaration bindings.
    * @return
    */
  def bindDeclarations(bindings: Bindings[WdlType]): Context = {
    this.copy(declarations = declarations.update(bindings))
  }

  def bindStruct(s: T_Struct): Context = {
    aliases.get(s.name) match {
      case Some(existingStruct: T_Struct) if s == existingStruct =>
        // The struct is defined a second time, with the exact same definition. Ignore.
        this
      case _ =>
        this.copy(aliases = aliases.add(s.name, s))
    }
  }

  // add a callable (task/workflow)
  def bindCallable(callable: T_Callable): Context = {
    this.copy(callables = callables.add(callable.name, callable))
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
                      importContext: Context,
                      typeAliases: Vector[AST.ImportAlias]): Context = {
    if (this.namespaces.contains(namespace)) {
      throw new DuplicateBindingException(s"namespace ${namespace} already exists")
    }

    // There cannot be any collisions because this is a new namespace
    val importCallables = importContext.callables.all.map {
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
    val aliasMapping: Map[String, String] = typeAliases.map {
      case AST.ImportAlias(src, dest, _) => src -> dest
    }.toMap
    val importAliases: Map[String, T_Struct] = importContext.aliases.all.map {
      case (name, importedStruct: T_Struct) =>
        aliasMapping.get(name) match {
          case None          => name -> importedStruct
          case Some(altName) => altName -> T_Struct(altName, importedStruct.members)
        }
      case (_, other) =>
        throw new RuntimeException(s"Expecting a struct but got ${other}")
    }

    // check that the imported structs do not step over existing definitions
    val (redefinedStructs, newStructs) = importAliases.partition {
      case (k, _) => aliases.contains(k)
    }
    val doublyDefinedStructs = redefinedStructs.collect {
      case (name, struct) if aliases(name) != struct => name
    }
    if (doublyDefinedStructs.nonEmpty) {
      throw new DuplicateBindingException(
          s"Struct(s) ${doublyDefinedStructs.mkString(",")} already defined in a different way"
      )
    }
    this.copy(aliases = aliases.update(newStructs),
              callables = callables.update(importCallables),
              namespaces = namespaces + namespace)
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
