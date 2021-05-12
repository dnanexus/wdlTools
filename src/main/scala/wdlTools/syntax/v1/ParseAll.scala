package wdlTools.syntax.v1

import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.v1.{ConcreteSyntax => CST}
import wdlTools.syntax.{Operator, SyntaxError, SyntaxException, WdlParser, AbstractSyntax => AST}
import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, Logger, StringFileNode}

import scala.collection.immutable.TreeSeqMap

// parse and follow imports
case class ParseAll(followImports: Boolean = false,
                    fileResolver: FileSourceResolver = FileSourceResolver.get,
                    listenerFactories: Vector[ParseTreeListenerFactory] = Vector.empty,
                    errorHandler: Option[Vector[SyntaxError] => Boolean] = None,
                    logger: Logger = Logger.get)
    extends WdlParser(followImports, fileResolver, logger) {

  private case class Translator(docSource: FileNode) {
    def translateType(cstType: CST.Type): AST.Type = {
      cstType match {
        case t: CST.TypeOptional =>
          AST.TypeOptional(translateType(t.t))(t.loc)
        case t: CST.TypeArray =>
          AST.TypeArray(translateType(t.t), t.nonEmpty)(t.loc)
        case t: CST.TypeMap =>
          AST.TypeMap(translateType(t.k), translateType(t.v))(t.loc)
        case t: CST.TypePair =>
          AST.TypePair(translateType(t.l), translateType(t.r))(t.loc)
        case t: CST.TypeString     => AST.TypeString()(t.loc)
        case t: CST.TypeFile       => AST.TypeFile()(t.loc)
        case t: CST.TypeBoolean    => AST.TypeBoolean()(t.loc)
        case t: CST.TypeInt        => AST.TypeInt()(t.loc)
        case t: CST.TypeFloat      => AST.TypeFloat()(t.loc)
        case t: CST.TypeIdentifier => AST.TypeIdentifier(t.id)(t.loc)
        case t: CST.TypeObject     => AST.TypeObject()(t.loc)
        case t: CST.TypeStruct =>
          AST.TypeStruct(t.name, t.members.map { member: CST.StructMember =>
            AST.StructMember(member.name, translateType(member.wdlType))(member.loc)
          })(t.loc)
      }
    }

    def translateExpr(cstExpr: CST.Expr): AST.Expr = {
      cstExpr match {
        // values
        case e: CST.ExprString  => AST.ValueString(e.value)(e.loc)
        case e: CST.ExprBoolean => AST.ValueBoolean(e.value)(e.loc)
        case e: CST.ExprInt     => AST.ValueInt(e.value)(e.loc)
        case e: CST.ExprFloat   => AST.ValueFloat(e.value)(e.loc)

        // compound values
        case e: CST.ExprIdentifier => AST.ExprIdentifier(e.id)(e.loc)
        case e: CST.ExprCompoundString =>
          AST.ExprCompoundString(e.value.map(translateExpr))(e.loc)
        case e: CST.ExprPair =>
          AST.ExprPair(translateExpr(e.l), translateExpr(e.r))(e.loc)
        case e: CST.ExprArrayLiteral =>
          AST.ExprArray(e.value.map(translateExpr))(e.loc)
        case e: CST.ExprMapLiteral =>
          AST.ExprMap(e.value.map { item =>
            AST.ExprMember(translateExpr(item.key), translateExpr(item.value))(item.loc)
          })(e.loc)
        case e: CST.ExprObjectLiteral =>
          AST.ExprObject(e.value.map { member =>
            AST.ExprMember(translateExpr(member.key), translateExpr(member.value))(member.loc)
          })(e.loc)

        // string place holders
        case e: CST.ExprPlaceholder =>
          AST.ExprPlaceholder(e.trueOpt.map(translateExpr),
                              e.falseOpt.map(translateExpr),
                              e.sepOpt.map(translateExpr),
                              e.defaultOpt.map(translateExpr),
                              translateExpr(e.value))(e.loc)

        // operators on one argument
        case e: CST.ExprUnaryPlus =>
          AST.ExprApply(Operator.UnaryPlus.name, Vector(translateExpr(e.value)))(e.loc)
        case e: CST.ExprUnaryMinus =>
          AST.ExprApply(Operator.UnaryMinus.name, Vector(translateExpr(e.value)))(e.loc)
        case e: CST.ExprNegate =>
          AST.ExprApply(Operator.LogicalNot.name, Vector(translateExpr(e.value)))(e.loc)

        // operators on two arguments
        case e: CST.ExprLor =>
          AST.ExprApply(Operator.LogicalOr.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprLand =>
          AST.ExprApply(Operator.LogicalAnd.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprEqeq =>
          AST.ExprApply(Operator.Equality.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprNeq =>
          AST.ExprApply(Operator.Inequality.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprLt =>
          AST.ExprApply(Operator.LessThan.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprLte =>
          AST.ExprApply(Operator.LessThanOrEqual.name,
                        Vector(translateExpr(e.a), translateExpr(e.b)))(e.loc)
        case e: CST.ExprGt =>
          AST.ExprApply(Operator.GreaterThan.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprGte =>
          AST.ExprApply(Operator.GreaterThanOrEqual.name,
                        Vector(translateExpr(e.a), translateExpr(e.b)))(e.loc)
        case e: CST.ExprAdd =>
          AST.ExprApply(Operator.Addition.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprSub =>
          AST.ExprApply(Operator.Subtraction.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprMul =>
          AST.ExprApply(Operator.Multiplication.name,
                        Vector(translateExpr(e.a), translateExpr(e.b)))(e.loc)
        case e: CST.ExprDivide =>
          AST.ExprApply(Operator.Division.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )
        case e: CST.ExprMod =>
          AST.ExprApply(Operator.Remainder.name, Vector(translateExpr(e.a), translateExpr(e.b)))(
              e.loc
          )

        // Access an array element at [index]
        case e: CST.ExprAt =>
          AST.ExprAt(translateExpr(e.array), translateExpr(e.index))(e.loc)

        case e: CST.ExprIfThenElse =>
          AST.ExprIfThenElse(translateExpr(e.cond),
                             translateExpr(e.tBranch),
                             translateExpr(e.fBranch))(
              e.loc
          )
        case e: CST.ExprApply =>
          if (Operator.All.contains(e.funcName)) {
            throw new SyntaxException(s"${e.funcName} is reserved and not a valid function name",
                                      e.loc)
          }
          AST.ExprApply(e.funcName, e.elements.map(translateExpr))(e.loc)
        case e: CST.ExprGetName =>
          AST.ExprGetName(translateExpr(e.expr), e.id)(e.loc)

        case other =>
          throw new Exception(s"invalid concrete syntax element ${other}")
      }
    }

    // The meta values are a subset of the expression syntax.
    //
    // $meta_value = $string | $number | $boolean | 'null' | $meta_object | $meta_array
    // $meta_object = '{}' | '{' $parameter_meta_kv (, $parameter_meta_kv)* '}'
    // $meta_array = '[]' |  '[' $meta_value (, $meta_value)* ']'
    //
    private def translateMetaValue(value: CST.MetaValue): AST.MetaValue = {
      value match {
        // values
        case v: CST.MetaValueString  => AST.MetaValueString(v.value)(v.loc)
        case v: CST.MetaValueBoolean => AST.MetaValueBoolean(v.value)(v.loc)
        case v: CST.MetaValueInt     => AST.MetaValueInt(v.value)(v.loc)
        case v: CST.MetaValueFloat   => AST.MetaValueFloat(v.value)(v.loc)
        case v: CST.MetaValueNull    => AST.MetaValueNull()(v.loc)
        case v: CST.MetaValueArray =>
          AST.MetaValueArray(v.value.map(translateMetaValue))(v.loc)
        case v: CST.MetaValueObject =>
          AST.MetaValueObject(v.value.map { kv: CST.MetaKV =>
            AST.MetaKV(kv.id, translateMetaValue(kv.value))(kv.loc)
          })(v.loc)
        case other =>
          throw new SyntaxException("illegal expression in meta section", other.loc)
      }
    }

    private def translateMetaKVs(kvs: Vector[CST.MetaKV],
                                 sectionName: String): Vector[AST.MetaKV] = {
      kvs
        .foldLeft(TreeSeqMap.empty[String, AST.MetaKV]) {
          case (accu, kv) =>
            val metaValue = translateMetaValue(kv.value)
            if (accu.contains(kv.id)) {
              logger.warning(
                  s"""duplicate ${sectionName} key ${kv.id}: earlier value ${accu(kv.id)}
                     |is overridden by later value ${metaValue}""".stripMargin.replaceAll("\n", " ")
              )
            }
            accu + (kv.id -> AST.MetaKV(kv.id, metaValue)(kv.loc))
        }
        .values
        .toVector
    }

    def translateInputSection(
        inp: CST.InputSection
    ): AST.InputSection = {
      AST.InputSection(inp.declarations.map(translateDeclaration))(inp.loc)
    }

    def translateOutputSection(
        output: CST.OutputSection
    ): AST.OutputSection = {
      AST.OutputSection(output.declarations.map(translateDeclaration))(output.loc)
    }

    def translateCommandSection(
        cs: CST.CommandSection
    ): AST.CommandSection = {
      AST.CommandSection(cs.parts.map(translateExpr))(cs.loc)
    }

    def translateDeclaration(decl: CST.Declaration): AST.Declaration = {
      AST.Declaration(decl.name, translateType(decl.wdlType), decl.expr.map(translateExpr))(
          decl.loc
      )
    }

    def translateMetaSection(meta: CST.MetaSection): AST.MetaSection = {
      AST.MetaSection(translateMetaKVs(meta.kvs, "meta"))(meta.loc)
    }

    def translateParameterMetaSection(
        paramMeta: CST.ParameterMetaSection
    ): AST.ParameterMetaSection = {
      AST.ParameterMetaSection(translateMetaKVs(paramMeta.kvs, "parameter_meta"))(paramMeta.loc)
    }

    def translateRuntimeSection(
        runtime: CST.RuntimeSection
    ): AST.RuntimeSection = {
      AST.RuntimeSection(
          runtime.kvs
            .foldLeft(TreeSeqMap.empty[String, AST.RuntimeKV]) {
              case (accu, kv: CST.RuntimeKV) =>
                val tExpr = translateExpr(kv.expr)
                if (accu.contains(kv.id)) {
                  logger.warning(
                      s"duplicate runtime key ${kv.id}: earlier value ${accu(kv.id)} is overridden by later value ${tExpr}"
                  )
                }
                accu + (kv.id -> AST.RuntimeKV(kv.id, tExpr)(kv.loc))
            }
            .values
            .toVector
      )(
          runtime.loc
      )
    }

    def translateWorkflowElement(
        elem: CST.WorkflowElement
    ): AST.WorkflowElement = {
      elem match {
        case decl: CST.Declaration =>
          AST.Declaration(decl.name, translateType(decl.wdlType), decl.expr.map(translateExpr))(
              decl.loc
          )

        case call: CST.Call =>
          AST.Call(
              call.name,
              call.alias.map { alias: CST.CallAlias =>
                AST.CallAlias(alias.name)(alias.loc)
              },
              Vector.empty,
              call.inputs.map { inputs: CST.CallInputs =>
                AST.CallInputs(inputs.value.map { inp =>
                  AST.CallInput(inp.name, translateExpr(inp.expr))(inp.loc)
                })(inputs.loc)
              }
          )(call.loc)

        case scatter: CST.Scatter =>
          AST.Scatter(scatter.identifier,
                      translateExpr(scatter.expr),
                      scatter.body.map(translateWorkflowElement))(scatter.loc)

        case cond: CST.Conditional =>
          AST.Conditional(translateExpr(cond.expr), cond.body.map(translateWorkflowElement))(
              cond.loc
          )
      }
    }

    def translateWorkflow(wf: CST.Workflow): AST.Workflow = {
      AST.Workflow(
          wf.name,
          wf.input.map(translateInputSection),
          wf.output.map(translateOutputSection),
          wf.meta.map(translateMetaSection),
          wf.parameterMeta.map(translateParameterMetaSection),
          wf.body.map(translateWorkflowElement)
      )(
          wf.loc
      )
    }

    def translateStruct(struct: CST.TypeStruct): AST.TypeStruct = {
      AST.TypeStruct(struct.name, struct.members.map { member: CST.StructMember =>
        AST.StructMember(member.name, translateType(member.wdlType))(member.loc)
      })(
          struct.loc
      )
    }

    def translateImportDoc(importDoc: CST.ImportDoc,
                           importedDoc: Option[AST.Document]): AST.ImportDoc = {
      val addrAbst = AST.ImportAddr(importDoc.addr.value)(importDoc.loc)
      val nameAbst = importDoc.name.map { impName: CST.ImportName =>
        AST.ImportName(impName.value)(impName.loc)
      }
      val aliasesAbst: Vector[AST.ImportAlias] = importDoc.aliases.map {
        impAlias: CST.ImportAlias =>
          AST.ImportAlias(impAlias.id1, impAlias.id2)(impAlias.loc)
      }

      // Replace the original statement with a new one
      AST.ImportDoc(nameAbst, aliasesAbst, addrAbst, importedDoc)(importDoc.loc)
    }

    def translateTask(task: CST.Task): AST.Task = {
      AST.Task(
          task.name,
          task.input.map(translateInputSection),
          task.output.map(translateOutputSection),
          translateCommandSection(task.command),
          task.declarations.map(translateDeclaration),
          task.meta.map(translateMetaSection),
          task.parameterMeta.map(translateParameterMetaSection),
          task.runtime.map(translateRuntimeSection),
          None
      )(
          task.loc
      )
    }

    // start from a document [doc], and recursively dive into all the imported
    // documents. Replace all the raw import statements with fully elaborated ones.
    def translateDocument(doc: ConcreteSyntax.Document): AST.Document = {
      // translate all the elements of the document to the abstract syntax
      val elems: Vector[AST.DocumentElement] = doc.elements.map {
        case struct: ConcreteSyntax.TypeStruct => translateStruct(struct)
        case importDoc: ConcreteSyntax.ImportDoc =>
          val importedDoc = if (followImports) {
            val parent = doc.source match {
              case fs: AddressableFileSource => fs.getParent
              case _                         => None
            }
            followImport(importDoc.addr.value, parent)
          } else {
            None
          }
          translateImportDoc(importDoc, importedDoc)
        case task: ConcreteSyntax.Task => translateTask(task)
        case other                     => throw new Exception(s"unrecognized document element ${other}")
      }
      val aWf = doc.workflow.map(translateWorkflow)
      val version = AST.Version(doc.version.value)(doc.version.loc)
      AST.Document(doc.source, version, elems, aWf, doc.comments)(doc.loc)
    }
  }

  private val versionRegexp = "version\\s+(1.0|draft-3).*".r

  override def canParse(fileSource: FileNode): Boolean = {
    fileSource.readLines.foreach { line =>
      if (!(line.trim.isEmpty || line.startsWith("#"))) {
        return versionRegexp.matches(line.trim)
      }
    }
    false
  }

  override def parseDocument(fileSource: FileNode): AST.Document = {
    val grammar = WdlV1Grammar.newInstance(fileSource, listenerFactories, logger)
    val visitor = ParseTop(grammar)
    val top: ConcreteSyntax.Document =
      try {
        visitor.parseDocument
      } catch {
        case ex: Throwable =>
          throw new SyntaxException(s"error parsing document ${fileSource.toString}", ex)
      }
    val errorListener = grammar.errListener
    if (errorListener.hasErrors && errorHandler
          .forall(eh => eh(errorListener.getErrors))) {
      throw new SyntaxException(errorListener.getErrors)
    }
    val translator = Translator(fileSource)
    translator.translateDocument(top)
  }

  override def parseExpr(text: String): AST.Expr = {
    val docSource = StringFileNode(text)
    val parser = ParseTop(WdlV1Grammar.newInstance(docSource, listenerFactories, logger))
    val translator = Translator(docSource)
    translator.translateExpr(parser.parseExpr)
  }

  override def parseType(text: String): AST.Type = {
    val docSource = StringFileNode(text)
    val parser = ParseTop(WdlV1Grammar.newInstance(docSource, listenerFactories, logger))
    val translator = Translator(docSource)
    translator.translateType(parser.parseWdlType)
  }
}
