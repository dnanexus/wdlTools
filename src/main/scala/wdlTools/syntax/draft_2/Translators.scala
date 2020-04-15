package wdlTools.syntax.draft_2

import wdlTools.syntax.AbstractSyntax

object Translators {
  def translateType(t: ConcreteSyntax.Type): AbstractSyntax.Type = {
    t match {
      case ConcreteSyntax.TypeOptional(t, srcText) =>
        AbstractSyntax.TypeOptional(translateType(t), srcText)
      case ConcreteSyntax.TypeArray(t, nonEmpty, srcText) =>
        AbstractSyntax.TypeArray(translateType(t), nonEmpty, srcText)
      case ConcreteSyntax.TypeMap(k, v, srcText) =>
        AbstractSyntax.TypeMap(translateType(k), translateType(v), srcText)
      case ConcreteSyntax.TypePair(l, r, srcText) =>
        AbstractSyntax.TypePair(translateType(l), translateType(r), srcText)
      case ConcreteSyntax.TypeString(srcText)         => AbstractSyntax.TypeString(srcText)
      case ConcreteSyntax.TypeFile(srcText)           => AbstractSyntax.TypeFile(srcText)
      case ConcreteSyntax.TypeBoolean(srcText)        => AbstractSyntax.TypeBoolean(srcText)
      case ConcreteSyntax.TypeInt(srcText)            => AbstractSyntax.TypeInt(srcText)
      case ConcreteSyntax.TypeFloat(srcText)          => AbstractSyntax.TypeFloat(srcText)
      case ConcreteSyntax.TypeIdentifier(id, srcText) => AbstractSyntax.TypeIdentifier(id, srcText)
      case ConcreteSyntax.TypeObject(srcText)         => AbstractSyntax.TypeObject(srcText)
    }
  }

  def translateExpr(e: ConcreteSyntax.Expr): AbstractSyntax.Expr = {
    e match {
      // values
      case ConcreteSyntax.ExprString(value, srcText)  => AbstractSyntax.ValueString(value, srcText)
      case ConcreteSyntax.ExprFile(value, srcText)    => AbstractSyntax.ValueFile(value, srcText)
      case ConcreteSyntax.ExprBoolean(value, srcText) => AbstractSyntax.ValueBoolean(value, srcText)
      case ConcreteSyntax.ExprInt(value, srcText)     => AbstractSyntax.ValueInt(value, srcText)
      case ConcreteSyntax.ExprFloat(value, srcText)   => AbstractSyntax.ValueFloat(value, srcText)

      // compound values
      case ConcreteSyntax.ExprIdentifier(id, srcText) => AbstractSyntax.ExprIdentifier(id, srcText)
      case ConcreteSyntax.ExprCompoundString(vec, srcText) =>
        AbstractSyntax.ExprCompoundString(vec.map(translateExpr), srcText)
      case ConcreteSyntax.ExprPair(l, r, srcText) =>
        AbstractSyntax.ExprPair(translateExpr(l), translateExpr(r), srcText)
      case ConcreteSyntax.ExprArrayLiteral(vec, srcText) =>
        AbstractSyntax.ExprArray(vec.map(translateExpr), srcText)
      case ConcreteSyntax.ExprMapLiteral(m, srcText) =>
        AbstractSyntax.ExprMap(m.map {
          case (k, v) => translateExpr(k) -> translateExpr(v)
        }, srcText)
      case ConcreteSyntax.ExprObjectLiteral(m, srcText) =>
        AbstractSyntax.ExprObject(m.map {
          case (fieldName, v) => fieldName -> translateExpr(v)
        }, srcText)

      // string place holders
      case ConcreteSyntax.ExprPlaceholderEqual(t, f, value, srcText) =>
        AbstractSyntax.ExprPlaceholderEqual(translateExpr(t),
                                            translateExpr(f),
                                            translateExpr(value),
                                            srcText)
      case ConcreteSyntax.ExprPlaceholderDefault(default, value, srcText) =>
        AbstractSyntax.ExprPlaceholderDefault(translateExpr(default), translateExpr(value), srcText)
      case ConcreteSyntax.ExprPlaceholderSep(sep, value, srcText) =>
        AbstractSyntax.ExprPlaceholderSep(translateExpr(sep), translateExpr(value), srcText)

      // operators on one argument
      case ConcreteSyntax.ExprUniraryPlus(value, srcText) =>
        AbstractSyntax.ExprUniraryPlus(translateExpr(value), srcText)
      case ConcreteSyntax.ExprUniraryMinus(value, srcText) =>
        AbstractSyntax.ExprUniraryMinus(translateExpr(value), srcText)
      case ConcreteSyntax.ExprNegate(value, srcText) =>
        AbstractSyntax.ExprNegate(translateExpr(value), srcText)

      // operators on two arguments
      case ConcreteSyntax.ExprLor(a, b, srcText) =>
        AbstractSyntax.ExprLor(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprLand(a, b, srcText) =>
        AbstractSyntax.ExprLand(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprEqeq(a, b, srcText) =>
        AbstractSyntax.ExprEqeq(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprLt(a, b, srcText) =>
        AbstractSyntax.ExprLt(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprGte(a, b, srcText) =>
        AbstractSyntax.ExprGte(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprNeq(a, b, srcText) =>
        AbstractSyntax.ExprNeq(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprLte(a, b, srcText) =>
        AbstractSyntax.ExprLte(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprGt(a, b, srcText) =>
        AbstractSyntax.ExprGt(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprAdd(a, b, srcText) =>
        AbstractSyntax.ExprAdd(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprSub(a, b, srcText) =>
        AbstractSyntax.ExprSub(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprMod(a, b, srcText) =>
        AbstractSyntax.ExprMod(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprMul(a, b, srcText) =>
        AbstractSyntax.ExprMul(translateExpr(a), translateExpr(b), srcText)
      case ConcreteSyntax.ExprDivide(a, b, srcText) =>
        AbstractSyntax.ExprDivide(translateExpr(a), translateExpr(b), srcText)

      // Access an array element at [index]
      case ConcreteSyntax.ExprAt(array, index, srcText) =>
        AbstractSyntax.ExprAt(translateExpr(array), translateExpr(index), srcText)

      case ConcreteSyntax.ExprIfThenElse(cond, tBranch, fBranch, srcText) =>
        AbstractSyntax.ExprIfThenElse(translateExpr(cond),
                                      translateExpr(tBranch),
                                      translateExpr(fBranch),
                                      srcText)
      case ConcreteSyntax.ExprApply(funcName, elements, srcText) =>
        AbstractSyntax.ExprApply(funcName, elements.map(translateExpr), srcText)
      case ConcreteSyntax.ExprGetName(e, id, srcText) =>
        AbstractSyntax.ExprGetName(translateExpr(e), id, srcText)

      case other =>
        throw new Exception(s"invalid concrete syntax element ${other}")
    }
  }

  def translateMetaKV(kv: ConcreteSyntax.MetaKV): AbstractSyntax.MetaKV = {
    AbstractSyntax.MetaKV(kv.id, AbstractSyntax.ValueString(kv.value, kv.text), kv.text, kv.comment)
  }

  def translateInputSection(
      inp: ConcreteSyntax.InputSection
  ): AbstractSyntax.InputSection = {
    AbstractSyntax.InputSection(inp.declarations.map(translateDeclaration), inp.text, inp.comment)
  }

  def translateOutputSection(
      output: ConcreteSyntax.OutputSection
  ): AbstractSyntax.OutputSection = {
    AbstractSyntax.OutputSection(output.declarations.map(translateDeclaration),
                                 output.text,
                                 output.comment)
  }

  def translateCommandSection(
      cs: ConcreteSyntax.CommandSection
  ): AbstractSyntax.CommandSection = {
    AbstractSyntax.CommandSection(cs.parts.map(translateExpr), cs.text, cs.comment)
  }

  def translateDeclaration(decl: ConcreteSyntax.Declaration): AbstractSyntax.Declaration = {
    AbstractSyntax.Declaration(decl.name,
                               translateType(decl.wdlType),
                               decl.expr.map(translateExpr),
                               decl.text,
                               decl.comment)
  }

  def translateMetaSection(meta: ConcreteSyntax.MetaSection): AbstractSyntax.MetaSection = {
    AbstractSyntax.MetaSection(meta.kvs.map(translateMetaKV), meta.text, meta.comment)
  }

  def translateParameterMetaSection(
      paramMeta: ConcreteSyntax.ParameterMetaSection
  ): AbstractSyntax.ParameterMetaSection = {
    AbstractSyntax.ParameterMetaSection(paramMeta.kvs.map(translateMetaKV),
                                        paramMeta.text,
                                        paramMeta.comment)
  }

  def translateRuntimeSection(
      runtime: ConcreteSyntax.RuntimeSection
  ): AbstractSyntax.RuntimeSection = {
    AbstractSyntax.RuntimeSection(
        runtime.kvs.map {
          case ConcreteSyntax.RuntimeKV(id, expr, text, comment) =>
            AbstractSyntax.RuntimeKV(id, translateExpr(expr), text, comment)
        },
        runtime.text,
        runtime.comment
    )
  }

  def translateWorkflowElement(
      elem: ConcreteSyntax.WorkflowElement
  ): AbstractSyntax.WorkflowElement = {
    elem match {
      case ConcreteSyntax.Declaration(name, wdlType, expr, text, comment) =>
        AbstractSyntax.Declaration(name,
                                   translateType(wdlType),
                                   expr.map(translateExpr),
                                   text,
                                   comment)

      case ConcreteSyntax.Call(name, alias, inputs, text, comment) =>
        AbstractSyntax.Call(name, alias, inputs.map {
          case (name, expr) => name -> translateExpr(expr)
        }, text, comment)

      case ConcreteSyntax.Scatter(identifier, expr, body, text, comment) =>
        AbstractSyntax.Scatter(identifier,
                               translateExpr(expr),
                               body.map(translateWorkflowElement),
                               text,
                               comment)

      case ConcreteSyntax.Conditional(expr, body, text, comment) =>
        AbstractSyntax.Conditional(translateExpr(expr),
                                   body.map(translateWorkflowElement),
                                   text,
                                   comment)
    }
  }

  def translateWorkflow(wf: ConcreteSyntax.Workflow): AbstractSyntax.Workflow = {
    AbstractSyntax.Workflow(
        wf.name,
        wf.input.map(translateInputSection),
        wf.output.map(translateOutputSection),
        wf.meta.map(translateMetaSection),
        wf.parameterMeta.map(translateParameterMetaSection),
        wf.body.map(translateWorkflowElement),
        wf.text,
        wf.comment
    )
  }

  def translateImportDoc(importDoc: ConcreteSyntax.ImportDoc,
                         importedDoc: AbstractSyntax.Document): AbstractSyntax.ImportDoc = {
    val aliasesAbst: Vector[AbstractSyntax.ImportAlias] = importDoc.aliases.map {
      case ConcreteSyntax.ImportAlias(x, y, alText) => AbstractSyntax.ImportAlias(x, y, alText)
    }

    // Replace the original statement with a new one
    AbstractSyntax.ImportDoc(importDoc.name,
                             aliasesAbst,
                             importDoc.url,
                             importedDoc,
                             importDoc.text,
                             importDoc.comment)
  }

  def translateTask(task: ConcreteSyntax.Task): AbstractSyntax.Task = {
    AbstractSyntax.Task(
        task.name,
        task.input.map(translateInputSection),
        task.output.map(translateOutputSection),
        translateCommandSection(task.command),
        task.declarations.map(translateDeclaration),
        task.meta.map(translateMetaSection),
        task.parameterMeta.map(translateParameterMetaSection),
        task.runtime.map(translateRuntimeSection),
        task.text,
        task.comment
    )
  }
}
