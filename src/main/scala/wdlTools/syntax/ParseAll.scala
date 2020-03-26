package wdlTools.syntax

import wdlTools.util.{FetchURL, Options, URL}

import scala.collection.mutable

// parse and follow imports
case class ParseAll(conf: Options) {
  // cache of documents that have already been fetched and parsed.
  private val docCache: mutable.Map[URL, AbstractSyntax.Document] = mutable.Map.empty

  private def followImport(url: URL): AbstractSyntax.Document = {
    docCache.get(url) match {
      case None =>
        val docText = FetchURL(conf).apply(url)
        val cDoc: ConcreteSyntax.Document =
          ParseDocument.apply(docText, conf)
        val aDoc = dfs(cDoc)
        docCache(url) = aDoc
        aDoc
      case Some(aDoc) =>
        aDoc
    }
  }

  private def translateType(t: ConcreteSyntax.Type): AbstractSyntax.Type = {
    t match {
      case ConcreteSyntax.TypeOptional(t) => AbstractSyntax.TypeOptional(translateType(t))
      case ConcreteSyntax.TypeArray(t, nonEmpty) =>
        AbstractSyntax.TypeArray(translateType(t), nonEmpty)
      case ConcreteSyntax.TypeMap(k, v) =>
        AbstractSyntax.TypeMap(translateType(k), translateType(v))
      case ConcreteSyntax.TypePair(l, r) =>
        AbstractSyntax.TypePair(translateType(l), translateType(r))
      case ConcreteSyntax.TypeString         => AbstractSyntax.TypeString
      case ConcreteSyntax.TypeFile           => AbstractSyntax.TypeFile
      case ConcreteSyntax.TypeBoolean        => AbstractSyntax.TypeBoolean
      case ConcreteSyntax.TypeInt            => AbstractSyntax.TypeInt
      case ConcreteSyntax.TypeFloat          => AbstractSyntax.TypeFloat
      case ConcreteSyntax.TypeIdentifier(id) => AbstractSyntax.TypeIdentifier(id)
      case ConcreteSyntax.TypeObject         => AbstractSyntax.TypeObject
      case ConcreteSyntax.TypeStruct(name, members) =>
        AbstractSyntax.TypeStruct(name, members.map {
          case (name, t) => name -> translateType(t)
        })
    }
  }

  private def translateExpr(e: ConcreteSyntax.Expr): AbstractSyntax.Expr = {
    e match {
      // values
      case ConcreteSyntax.ExprString(value)  => AbstractSyntax.ValueString(value)
      case ConcreteSyntax.ExprFile(value)    => AbstractSyntax.ValueFile(value)
      case ConcreteSyntax.ExprBoolean(value) => AbstractSyntax.ValueBoolean(value)
      case ConcreteSyntax.ExprInt(value)     => AbstractSyntax.ValueInt(value)
      case ConcreteSyntax.ExprFloat(value)   => AbstractSyntax.ValueFloat(value)

      // compound values
      case ConcreteSyntax.ExprIdentifier(id) => AbstractSyntax.ExprIdentifier(id)
      case ConcreteSyntax.ExprCompoundString(vec) =>
        AbstractSyntax.ExprCompoundString(vec.map(translateExpr))
      case ConcreteSyntax.ExprPair(l, r) =>
        AbstractSyntax.ExprPair(translateExpr(l), translateExpr(r))
      case ConcreteSyntax.ExprArrayLiteral(vec) =>
        AbstractSyntax.ExprArray(vec.map(translateExpr))
      case ConcreteSyntax.ExprMapLiteral(m) =>
        AbstractSyntax.ExprMap(m.map {
          case (k, v) => translateExpr(k) -> translateExpr(v)
        })
      case ConcreteSyntax.ExprObjectLiteral(m) =>
        AbstractSyntax.ExprObject(m.map {
          case (fieldName, v) => fieldName -> translateExpr(v)
        })

      // string place holders
      case ConcreteSyntax.ExprPlaceholderEqual(t, f, value) =>
        AbstractSyntax.ExprPlaceholderEqual(translateExpr(t),
                                            translateExpr(f),
                                            translateExpr(value))
      case ConcreteSyntax.ExprPlaceholderDefault(default, value) =>
        AbstractSyntax.ExprPlaceholderDefault(translateExpr(default), translateExpr(value))
      case ConcreteSyntax.ExprPlaceholderSep(sep, value) =>
        AbstractSyntax.ExprPlaceholderSep(translateExpr(sep), translateExpr(value))

      // operators on one argument
      case ConcreteSyntax.ExprUniraryPlus(value) =>
        AbstractSyntax.ExprUniraryPlus(translateExpr(value))
      case ConcreteSyntax.ExprUniraryMinus(value) =>
        AbstractSyntax.ExprUniraryMinus(translateExpr(value))
      case ConcreteSyntax.ExprNegate(value) =>
        AbstractSyntax.ExprNegate(translateExpr(value))

      // operators on two arguments
      case ConcreteSyntax.ExprLor(a, b) =>
        AbstractSyntax.ExprLor(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLand(a, b) =>
        AbstractSyntax.ExprLand(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprEqeq(a, b) =>
        AbstractSyntax.ExprEqeq(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLt(a, b) => AbstractSyntax.ExprLt(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprGte(a, b) =>
        AbstractSyntax.ExprGte(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprNeq(a, b) =>
        AbstractSyntax.ExprNeq(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLte(a, b) =>
        AbstractSyntax.ExprLte(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprGt(a, b) => AbstractSyntax.ExprGt(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprAdd(a, b) =>
        AbstractSyntax.ExprAdd(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprSub(a, b) =>
        AbstractSyntax.ExprSub(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprMod(a, b) =>
        AbstractSyntax.ExprMod(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprMul(a, b) =>
        AbstractSyntax.ExprMul(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprDivide(a, b) =>
        AbstractSyntax.ExprDivide(translateExpr(a), translateExpr(b))

      // Access an array element at [index]
      case ConcreteSyntax.ExprAt(array, index) =>
        AbstractSyntax.ExprAt(translateExpr(array), translateExpr(index))

      case ConcreteSyntax.ExprIfThenElse(cond, tBranch, fBranch) =>
        AbstractSyntax.ExprIfThenElse(translateExpr(cond),
                                      translateExpr(tBranch),
                                      translateExpr(fBranch))
      case ConcreteSyntax.ExprApply(funcName, elements) =>
        AbstractSyntax.ExprApply(funcName, elements.map(translateExpr))
      case ConcreteSyntax.ExprGetName(e, id) =>
        AbstractSyntax.ExprGetName(translateExpr(e), id)

      case other =>
        throw new Exception(s"invalid concrete syntax element ${other}")
    }
  }

  private def translateMetaKV(kv: ConcreteSyntax.MetaKV): AbstractSyntax.MetaKV = {
    AbstractSyntax.MetaKV(kv.id, translateExpr(kv.expr))
  }

  private def translateInputSection(
      inp: ConcreteSyntax.InputSection
  ): AbstractSyntax.InputSection = {
    AbstractSyntax.InputSection(inp.declarations.map(translateDeclaration))
  }

  private def translateOutputSection(
      output: ConcreteSyntax.OutputSection
  ): AbstractSyntax.OutputSection = {
    AbstractSyntax.OutputSection(output.declarations.map(translateDeclaration))
  }

  private def translateCommandSection(
      cs: ConcreteSyntax.CommandSection
  ): AbstractSyntax.CommandSection = {
    AbstractSyntax.CommandSection(cs.parts.map(translateExpr))
  }

  private def translateDeclaration(decl: ConcreteSyntax.Declaration): AbstractSyntax.Declaration = {
    AbstractSyntax.Declaration(decl.name, translateType(decl.wdlType), decl.expr.map(translateExpr))
  }

  private def translateMetaSection(meta: ConcreteSyntax.MetaSection): AbstractSyntax.MetaSection = {
    AbstractSyntax.MetaSection(meta.kvs.map(translateMetaKV))
  }

  private def translateParameterMetaSection(
      paramMeta: ConcreteSyntax.ParameterMetaSection
  ): AbstractSyntax.ParameterMetaSection = {
    AbstractSyntax.ParameterMetaSection(paramMeta.kvs.map(translateMetaKV))
  }

  private def translateRuntimeSection(
      runtime: ConcreteSyntax.RuntimeSection
  ): AbstractSyntax.RuntimeSection = {
    AbstractSyntax.RuntimeSection(runtime.kvs.map {
      case ConcreteSyntax.RuntimeKV(id, expr) => AbstractSyntax.RuntimeKV(id, translateExpr(expr))
    })
  }

  private def translateWorkflowElement(
      elem: ConcreteSyntax.WorkflowElement
  ): AbstractSyntax.WorkflowElement = {
    elem match {
      case ConcreteSyntax.Declaration(name, wdlType, expr) =>
        AbstractSyntax.Declaration(name, translateType(wdlType), expr.map(translateExpr))

      case ConcreteSyntax.Call(name, alias, inputs) =>
        AbstractSyntax.Call(name, alias, inputs.map {
          case (name, expr) => name -> translateExpr(expr)
        })

      case ConcreteSyntax.Scatter(identifier, expr, body) =>
        AbstractSyntax.Scatter(identifier, translateExpr(expr), body.map(translateWorkflowElement))

      case ConcreteSyntax.Conditional(expr, body) =>
        AbstractSyntax.Conditional(translateExpr(expr), body.map(translateWorkflowElement))
    }
  }

  private def translateWorkflow(wf: ConcreteSyntax.Workflow): AbstractSyntax.Workflow = {
    AbstractSyntax.Workflow(
        wf.name,
        wf.input.map(translateInputSection),
        wf.output.map(translateOutputSection),
        wf.meta.map(translateMetaSection),
        wf.parameterMeta.map(translateParameterMetaSection),
        wf.body.map(translateWorkflowElement)
    )
  }

  // start from a document [doc], and recursively dive into all the imported
  // documents. Replace all the raw import statements with fully elaborated ones.
  private def dfs(doc: ConcreteSyntax.Document): AbstractSyntax.Document = {

    // translate all the elements of the document to the abstract syntax
    val elems: Vector[AbstractSyntax.DocumentElement] = doc.elements.map {
      case ConcreteSyntax.TypeStruct(name, members) =>
        AbstractSyntax.TypeStruct(name, members.map { case (name, t) => name -> translateType(t) })

      case ConcreteSyntax.ImportDoc(name, aliases, url) =>
        val importedDoc = followImport(url)
        val aliasesAbst: Vector[AbstractSyntax.ImportAlias] = aliases.map {
          case ConcreteSyntax.ImportAlias(x, y) => AbstractSyntax.ImportAlias(x, y)
        }

        // Replace the original statement with a new one
        AbstractSyntax.ImportDoc(name, aliasesAbst, url, importedDoc)

      case ConcreteSyntax.Task(name,
                               input,
                               output,
                               command,
                               declarations,
                               meta,
                               parameterMeta,
                               runtime) =>
        AbstractSyntax.Task(
            name,
            input.map(translateInputSection),
            output.map(translateOutputSection),
            translateCommandSection(command),
            declarations.map(translateDeclaration),
            meta.map(translateMetaSection),
            parameterMeta.map(translateParameterMetaSection),
            runtime.map(translateRuntimeSection)
        )

      case other => throw new Exception(s"unrecognized document element ${other}")
    }

    val aWf = doc.workflow.map(translateWorkflow)
    AbstractSyntax.Document(doc.version, elems, aWf)
  }

  // [dirs] : the directories where to search for imported documents
  //
  def apply(sourceCode: String): AbstractSyntax.Document = {
    val top: ConcreteSyntax.Document = ParseDocument.apply(sourceCode, conf)
    dfs(top)
  }
}
