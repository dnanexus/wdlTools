package wdlTools.syntax.v2

import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.v2.{ConcreteSyntax => CST}
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
    def translateType(t: CST.Type): AST.Type = {
      t match {
        case CST.TypeOptional(t, loc) =>
          AST.TypeOptional(translateType(t), loc)
        case CST.TypeArray(t, nonEmpty, loc) =>
          AST.TypeArray(translateType(t), nonEmpty, loc)
        case CST.TypeMap(k, v, loc) =>
          AST.TypeMap(translateType(k), translateType(v), loc)
        case CST.TypePair(l, r, loc) =>
          AST.TypePair(translateType(l), translateType(r), loc)
        case CST.TypeString(loc)         => AST.TypeString(loc)
        case CST.TypeFile(loc)           => AST.TypeFile(loc)
        case CST.TypeDirectory(loc)      => AST.TypeDirectory(loc)
        case CST.TypeBoolean(loc)        => AST.TypeBoolean(loc)
        case CST.TypeInt(loc)            => AST.TypeInt(loc)
        case CST.TypeFloat(loc)          => AST.TypeFloat(loc)
        case CST.TypeIdentifier(id, loc) => AST.TypeIdentifier(id, loc)
        case CST.TypeStruct(name, members, loc) =>
          AST.TypeStruct(name, members.map {
            case CST.StructMember(name, t, text) =>
              AST.StructMember(name, translateType(t), text)
          }, loc)
      }
    }

    def translateExpr(e: CST.Expr): AST.Expr = {
      e match {
        // value
        case CST.ExprNone(loc)           => AST.ValueNone(loc)
        case CST.ExprString(value, loc)  => AST.ValueString(value, loc)
        case CST.ExprBoolean(value, loc) => AST.ValueBoolean(value, loc)
        case CST.ExprInt(value, loc)     => AST.ValueInt(value, loc)
        case CST.ExprFloat(value, loc)   => AST.ValueFloat(value, loc)

        // compound values
        case CST.ExprIdentifier(id, loc) => AST.ExprIdentifier(id, loc)
        case CST.ExprCompoundString(parts, loc) =>
          AST.ExprCompoundString(parts.map(translateExpr), loc)
        case CST.ExprPair(left, right, loc) =>
          AST.ExprPair(translateExpr(left), translateExpr(right), loc)
        case CST.ExprArrayLiteral(array, loc) =>
          AST.ExprArray(array.map(translateExpr), loc)
        case CST.ExprMapLiteral(members, loc) =>
          AST.ExprMap(members.map { item =>
            AST.ExprMember(translateExpr(item.key), translateExpr(item.value), item.loc)
          }, loc)
        case CST.ExprObjectLiteral(members, loc) =>
          AST.ExprObject(members.map { member =>
            AST.ExprMember(translateExpr(member.key), translateExpr(member.value), member.loc)
          }, loc)
        case CST.ExprStructLiteral(name, members, loc) =>
          AST.ExprStruct(name, members.map { member =>
            AST.ExprMember(translateExpr(member.key), translateExpr(member.value), member.loc)
          }, loc)

        // operators on one argument
        case CST.ExprUnaryPlus(value, loc) =>
          AST.ExprApply(Operator.UnaryPlus.name, Vector(translateExpr(value)), loc)
        case CST.ExprUnaryMinus(value, loc) =>
          AST.ExprApply(Operator.UnaryMinus.name, Vector(translateExpr(value)), loc)
        case CST.ExprNegate(value, loc) =>
          AST.ExprApply(Operator.LogicalNot.name, Vector(translateExpr(value)), loc)

        // operators on two arguments
        case CST.ExprLor(a, b, loc) =>
          AST.ExprApply(Operator.LogicalOr.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprLand(a, b, loc) =>
          AST.ExprApply(Operator.LogicalAnd.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprEqeq(a, b, loc) =>
          AST.ExprApply(Operator.Equality.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprNeq(a, b, loc) =>
          AST.ExprApply(Operator.Inequality.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprLt(a, b, loc) =>
          AST.ExprApply(Operator.LessThan.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprLte(a, b, loc) =>
          AST.ExprApply(Operator.LessThanOrEqual.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        loc)
        case CST.ExprGt(a, b, loc) =>
          AST.ExprApply(Operator.GreaterThan.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprGte(a, b, loc) =>
          AST.ExprApply(Operator.GreaterThanOrEqual.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        loc)
        case CST.ExprAdd(a, b, loc) =>
          AST.ExprApply(Operator.Addition.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprSub(a, b, loc) =>
          AST.ExprApply(Operator.Subtraction.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprMul(a, b, loc) =>
          AST.ExprApply(Operator.Multiplication.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        loc)
        case CST.ExprDivide(a, b, loc) =>
          AST.ExprApply(Operator.Division.name, Vector(translateExpr(a), translateExpr(b)), loc)
        case CST.ExprMod(a, b, loc) =>
          AST.ExprApply(Operator.Remainder.name, Vector(translateExpr(a), translateExpr(b)), loc)

        // Access an array element at [index]
        case CST.ExprAt(array, index, loc) =>
          AST.ExprAt(translateExpr(array), translateExpr(index), loc)

        case CST.ExprIfThenElse(cond, tBranch, fBranch, loc) =>
          AST.ExprIfThenElse(translateExpr(cond),
                             translateExpr(tBranch),
                             translateExpr(fBranch),
                             loc)
        case CST.ExprApply(funcName, elements, loc) =>
          if (Operator.All.contains(funcName)) {
            throw new SyntaxException(s"${funcName} is reserved and not a valid function name", loc)
          }
          AST.ExprApply(funcName, elements.map(translateExpr), loc)
        case CST.ExprGetName(e, id, loc) =>
          AST.ExprGetName(translateExpr(e), id, loc)

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
        case CST.MetaValueString(value, loc)  => AST.MetaValueString(value, loc)
        case CST.MetaValueBoolean(value, loc) => AST.MetaValueBoolean(value, loc)
        case CST.MetaValueInt(value, loc)     => AST.MetaValueInt(value, loc)
        case CST.MetaValueFloat(value, loc)   => AST.MetaValueFloat(value, loc)
        case CST.MetaValueNull(loc)           => AST.MetaValueNull(loc)
        case CST.MetaValueArray(vec, loc) =>
          AST.MetaValueArray(vec.map(translateMetaValue), loc)
        case CST.MetaValueObject(m, loc) =>
          AST.MetaValueObject(m.map {
            case CST.MetaKV(fieldName: String, v, text) =>
              AST.MetaKV(fieldName, translateMetaValue(v), text)
          }, loc)
        case other =>
          throw new SyntaxException("illegal expression in meta section", other.loc)
      }
    }

    private def translateInputSection(
        inp: CST.InputSection
    ): AST.InputSection = {
      AST.InputSection(inp.declarations.map(translateDeclaration), inp.loc)
    }

    private def translateOutputSection(
        output: CST.OutputSection
    ): AST.OutputSection = {
      AST.OutputSection(output.declarations.map(translateDeclaration), output.loc)
    }

    private def translateCommandSection(
        cs: CST.CommandSection
    ): AST.CommandSection = {
      AST.CommandSection(cs.parts.map(translateExpr), cs.loc)
    }

    private def translateDeclaration(decl: CST.Declaration): AST.Declaration = {
      AST.Declaration(decl.name,
                      translateType(decl.wdlType),
                      decl.expr.map(translateExpr),
                      decl.loc)
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
            accu + (kv.id -> AST.MetaKV(kv.id, metaValue, kv.loc))
        }
        .values
        .toVector
    }

    private def translateMetaSection(meta: CST.MetaSection): AST.MetaSection = {
      AST.MetaSection(translateMetaKVs(meta.kvs, "meta"), meta.loc)
    }

    private def translateParameterMetaSection(
        paramMeta: CST.ParameterMetaSection
    ): AST.ParameterMetaSection = {
      AST.ParameterMetaSection(translateMetaKVs(paramMeta.kvs, "parameter_meta"), paramMeta.loc)
    }

    private def translateHintsSection(
        hints: CST.HintsSection
    ): AST.HintsSection = {
      AST.HintsSection(translateMetaKVs(hints.kvs, "hints"), hints.loc)
    }

    private def translateRuntimeSection(
        runtime: CST.RuntimeSection
    ): AST.RuntimeSection = {
      AST.RuntimeSection(
          runtime.kvs
            .foldLeft(TreeSeqMap.empty[String, AST.RuntimeKV]) {
              case (accu, CST.RuntimeKV(id, expr, text)) =>
                val tExpr = translateExpr(expr)
                if (accu.contains(id)) {
                  logger.warning(
                      s"duplicate runtime key ${id}: earlier value ${accu(id)} is overridden by later value ${tExpr}"
                  )
                }
                accu + (id -> AST.RuntimeKV(id, tExpr, text))
            }
            .values
            .toVector,
          runtime.loc
      )
    }

    private def translateWorkflowElement(
        elem: CST.WorkflowElement
    ): AST.WorkflowElement = {
      elem match {
        case CST.Declaration(name, wdlType, expr, text) =>
          AST.Declaration(name, translateType(wdlType), expr.map(translateExpr), text)

        case CST.Call(name, alias, afters, inputs, text) =>
          AST.Call(
              name,
              alias.map {
                case CST.CallAlias(callName, callText) =>
                  AST.CallAlias(callName, callText)
              },
              afters.map {
                case CST.CallAfter(afterName, afterText) =>
                  AST.CallAfter(afterName, afterText)
              },
              inputs.map {
                case CST.CallInputs(inputsMap, inputsText) =>
                  AST.CallInputs(inputsMap.map { inp =>
                    AST.CallInput(inp.name, translateExpr(inp.expr), inp.loc)
                  }, inputsText)
              },
              text
          )

        case CST.Scatter(identifier, expr, body, text) =>
          AST.Scatter(identifier, translateExpr(expr), body.map(translateWorkflowElement), text)

        case CST.Conditional(expr, body, text) =>
          AST.Conditional(translateExpr(expr), body.map(translateWorkflowElement), text)
      }
    }

    private def translateWorkflow(wf: CST.Workflow): AST.Workflow = {
      AST.Workflow(
          wf.name,
          wf.input.map(translateInputSection),
          wf.output.map(translateOutputSection),
          wf.meta.map(translateMetaSection),
          wf.parameterMeta.map(translateParameterMetaSection),
          wf.body.map(translateWorkflowElement),
          wf.loc
      )
    }

    private def translateStruct(struct: CST.TypeStruct): AST.TypeStruct = {
      AST.TypeStruct(
          struct.name,
          struct.members.map {
            case CST.StructMember(name, t, memberText) =>
              AST.StructMember(name, translateType(t), memberText)
          },
          struct.loc
      )
    }

    private def translateImportDoc(importDoc: CST.ImportDoc,
                                   importedDoc: Option[AST.Document]): AST.ImportDoc = {
      val addrAbst = AST.ImportAddr(importDoc.addr.value, importDoc.loc)
      val nameAbst = importDoc.name.map {
        case CST.ImportName(value, text) => AST.ImportName(value, text)
      }
      val aliasesAbst: Vector[AST.ImportAlias] = importDoc.aliases.map {
        case CST.ImportAlias(x, y, alText) => AST.ImportAlias(x, y, alText)
      }

      // Replace the original statement with a new one
      AST.ImportDoc(nameAbst, aliasesAbst, addrAbst, importedDoc, importDoc.loc)
    }

    private def translateTask(task: CST.Task): AST.Task = {
      AST.Task(
          task.name,
          task.input.map(translateInputSection),
          task.output.map(translateOutputSection),
          translateCommandSection(task.command),
          task.declarations.map(translateDeclaration),
          task.meta.map(translateMetaSection),
          task.parameterMeta.map(translateParameterMetaSection),
          task.runtime.map(translateRuntimeSection),
          task.hints.map(translateHintsSection),
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
      val version = AST.Version(doc.version.value, doc.version.loc)
      AST.Document(doc.source, version, elems, aWf, doc.loc, doc.comments)
    }
  }

  private val versionRegexp = "version\\s+(2.0|development).*".r

  override def canParse(fileSource: FileNode): Boolean = {
    fileSource.readLines.foreach { line =>
      if (!(line.trim.isEmpty || line.startsWith("#"))) {
        return versionRegexp.matches(line.trim)
      }
    }
    false
  }

  override def parseDocument(fileSource: FileNode): AST.Document = {
    val grammar = WdlV2Grammar.newInstance(fileSource, listenerFactories, logger)
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
    val parser = ParseTop(WdlV2Grammar.newInstance(docSource, listenerFactories, logger))
    val translator = Translator(docSource)
    translator.translateExpr(parser.parseExpr)
  }

  override def parseType(text: String): AST.Type = {
    val docSource = StringFileNode(text)
    val parser = ParseTop(WdlV2Grammar.newInstance(docSource, listenerFactories, logger))
    val translator = Translator(docSource)
    translator.translateType(parser.parseWdlType)
  }
}
