package wdlTools.generators.project

import wdlTools.generators.Renderer
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.SyntaxUtils.prettyFormatExpr
import wdlTools.syntax.{Comment, Parsers, SyntaxUtils}
import wdlTools.util.{FileNode, FileSourceResolver, FileUtils}

object DocumentationGenerator {
  //private val TemplatePrefix = "/templates/documentation/"
  private val DocumentTemplate = "/templates/documentation/document.ssp"
  private val StructsTemplate = "/templates/documentation/structs.ssp"
  private val IndexTemplate = "/templates/documentation/index.ssp"

  case class DocumentationComment(comments: Vector[Comment]) {
    private val commentRegex = "#+\\s*(.*)".r
    override def toString: String = {
      comments
        .map(_.value)
        .collect {
          case commentRegex(s) => s.trim
        }
        .mkString(" ")
    }
  }

  private def formatMetaValue(metaValue: ValueDocumentation, indent: String = ""): String = {
    metaValue match {
      case SimpleValueDocumentation(value, comment) =>
        val s: String = value match {
          case e: Expr      => SyntaxUtils.prettyFormatExpr(e)
          case v: MetaValue => SyntaxUtils.prettyFormatMetaValue(v)
          case other        => other.toString
        }
        if (comment.isDefined) {
          s"${s} (${comment.get})"
        } else {
          s
        }
      case ListValueDocumentation(value, _) =>
        s"${value.map(x => formatMetaValue(x, indent + "    ")).mkString(", ")}"
      case MapValueDocumentation(value, _) =>
        val indent2 = s"${indent}    "
        val items =
          value.map(x => s"${indent2}* ${x._1}: ${formatMetaValue(x._2, indent2 + "    ")}")
        s"\n${items.mkString("\n")}"
    }
  }

  case class ImportDocumentation(addr: String,
                                 name: String,
                                 aliases: Map[String, String],
                                 comment: Option[DocumentationComment])

  trait ValueDocumentation {
    val comment: Option[DocumentationComment]

    override def toString: String = formatMetaValue(this)
  }

  case class SimpleValueDocumentation(value: Element, comment: Option[DocumentationComment])
      extends ValueDocumentation

  case class MapValueDocumentation(value: Map[String, ValueDocumentation],
                                   comment: Option[DocumentationComment])
      extends ValueDocumentation

  case class ListValueDocumentation(value: Vector[ValueDocumentation],
                                    comment: Option[DocumentationComment])
      extends ValueDocumentation

  case class KeyValueDocumentation(key: String,
                                   value: ValueDocumentation,
                                   comment: Option[DocumentationComment])

  case class DeclarationDocumentation(name: String,
                                      wdlType: String,
                                      defaultValue: Option[String] = None,
                                      meta: Option[ValueDocumentation] = None,
                                      comment: Option[DocumentationComment])

  case class StructDocumentation(name: String,
                                 members: Vector[DeclarationDocumentation],
                                 comment: Option[DocumentationComment])

  case class CallDocumentation(name: String, comment: Option[DocumentationComment])

  case class WorkflowDocumentation(name: String,
                                   inputs: Vector[DeclarationDocumentation],
                                   outputs: Vector[DeclarationDocumentation],
                                   calls: Vector[CallDocumentation],
                                   meta: Vector[KeyValueDocumentation],
                                   comment: Option[DocumentationComment])

  case class TaskDocumentation(name: String,
                               inputs: Vector[DeclarationDocumentation],
                               outputs: Vector[DeclarationDocumentation],
                               runtime: Vector[KeyValueDocumentation],
                               hints: Vector[KeyValueDocumentation],
                               meta: Vector[KeyValueDocumentation],
                               comment: Option[DocumentationComment])

  case class WdlDocumentation(source: FileNode,
                              imports: Vector[ImportDocumentation],
                              structs: Vector[StructDocumentation],
                              workflow: Option[WorkflowDocumentation],
                              tasks: Vector[TaskDocumentation],
                              comment: Option[DocumentationComment])

  def generateDocumentation(doc: Document): Option[WdlDocumentation] = {
    val sortedElements = (doc.elements ++ doc.workflow.map(Vector(_)).getOrElse(Vector.empty))
      .sortWith(_.loc < _.loc)
    if (sortedElements.nonEmpty) {
      def getDocumentationComment(element: Element): Option[DocumentationComment] = {
        val preceedingComments =
          (1 until element.loc.line).reverse
            .map(doc.comments.get)
            .takeWhile(comment => comment.isDefined && comment.get.value.startsWith("###"))
            .reverse
            .flatten
            .toVector
        if (preceedingComments.nonEmpty) {
          Some(DocumentationComment(preceedingComments))
        } else {
          None
        }
      }

      def getMetaValueDocumentation(value: MetaValue, parentLine: Int): ValueDocumentation = {
        val comment = if (value.loc.line > parentLine) {
          getDocumentationComment(value)
        } else {
          None
        }
        value match {
          case MetaValueArray(value, text) =>
            ListValueDocumentation(value.map(v => getMetaValueDocumentation(v, text.line)), comment)
          case MetaValueObject(value, text) =>
            MapValueDocumentation(
                value.map(v => v.id -> getMetaValueDocumentation(v.value, text.line)).toMap,
                comment
            )
          case other => SimpleValueDocumentation(other, comment)
        }
      }

      def getValueDocumentation(
          value: Expr,
          parentLine: Int
      ): ValueDocumentation = {
        val comment = if (value.loc.line > parentLine) {
          getDocumentationComment(value)
        } else {
          None
        }
        value match {
          case ExprObject(value, text) =>
            DocumentationGenerator.MapValueDocumentation(
                value
                  .map(v => prettyFormatExpr(v.key) -> getValueDocumentation(v.value, text.line))
                  .toMap,
                comment
            )
          case ExprMap(value, text) =>
            DocumentationGenerator.MapValueDocumentation(
                value
                  .map(v => prettyFormatExpr(v.key) -> getValueDocumentation(v.value, text.line))
                  .toMap,
                comment
            )
          case ExprArray(value, text) =>
            ListValueDocumentation(value.map(v => getValueDocumentation(v, text.line)), comment)
          case ExprPair(left, right, text) =>
            DocumentationGenerator.ListValueDocumentation(
                Vector(
                    getValueDocumentation(left, text.line),
                    getValueDocumentation(right, text.line)
                ),
                comment
            )
          case other => SimpleValueDocumentation(other, comment)
        }
      }

      def getMetaDocumentation(meta: MetaSection): Vector[KeyValueDocumentation] = {
        meta.kvs.map { kv =>
          KeyValueDocumentation(kv.id,
                                getMetaValueDocumentation(kv.value, kv.loc.line),
                                getDocumentationComment(kv))
        }
      }

      def wdlTypeToString(wdlType: Type): String = {
        wdlType match {
          case TypeOptional(t, _) => s"${wdlTypeToString(t)}?"
          case TypeArray(t, nonEmpty, _) =>
            s"Array[${wdlTypeToString(t)}]${if (nonEmpty) "+" else ""}"
          case TypeMap(k, v, _)       => s"Map[${wdlTypeToString(k)}, ${wdlTypeToString(v)}]"
          case TypePair(l, r, _)      => s"Pair[${wdlTypeToString(l)}, ${wdlTypeToString(r)}]"
          case TypeString(_)          => "String"
          case TypeFile(_)            => "File"
          case TypeDirectory(_)       => "Directory"
          case TypeBoolean(_)         => "Boolean"
          case TypeInt(_)             => "Int"
          case TypeFloat(_)           => "Float"
          case TypeIdentifier(id, _)  => s"[${id}](#${id})"
          case TypeObject(_)          => "Object"
          case TypeStruct(name, _, _) => s"[${name}](#${name})"
        }
      }

      def getDeclarationDocumentation(
          decls: Vector[Declaration],
          meta: Option[ParameterMetaSection],
          defaultAllowed: Boolean = true
      ): Vector[DeclarationDocumentation] = {
        decls.map { d =>
          val paramMeta = meta.flatMap(_.kvs.collectFirst {
            case kv: MetaKV if kv.id == d.name => kv.value
          })
          val default = if (defaultAllowed) {
            d.expr.map(e => prettyFormatExpr(e))
          } else {
            None
          }
          DeclarationDocumentation(
              d.name,
              wdlTypeToString(d.wdlType),
              default,
              paramMeta.map(v => getMetaValueDocumentation(v, meta.get.loc.line)),
              getDocumentationComment(d)
          )
        }
      }

      def getCallDocumentation(body: Vector[WorkflowElement]): Vector[CallDocumentation] = {
        body.collect {
          case c: Call        => Vector(CallDocumentation(c.name, getDocumentationComment(c)))
          case s: Scatter     => getCallDocumentation(s.body)
          case c: Conditional => getCallDocumentation(c.body)
        }.flatten
      }

      val imports = sortedElements.collect {
        case imp: ImportDoc =>
          ImportDocumentation(
              imp.addr.value,
              imp.name
                .map(_.value)
                .getOrElse(
                    FileUtils.changeFileExt(FileSourceResolver.get.resolve(imp.addr.value).name,
                                            dropExt = ".wdl")
                ),
              imp.aliases.map(a => a.id1 -> a.id2).toMap,
              getDocumentationComment(imp)
          )
      }
      val structs = sortedElements.collect {
        case struct: TypeStruct =>
          StructDocumentation(
              struct.name,
              struct.members.map(m => {
                DeclarationDocumentation(m.name,
                                         wdlTypeToString(m.wdlType),
                                         comment = getDocumentationComment(m))
              }),
              getDocumentationComment(struct)
          )
      }
      val workflow = sortedElements.collect {
        case wf: Workflow =>
          WorkflowDocumentation(
              wf.name,
              wf.input
                .map(inp => getDeclarationDocumentation(inp.parameters, wf.parameterMeta))
                .getOrElse(Vector.empty),
              wf.output
                .map(inp =>
                  getDeclarationDocumentation(inp.parameters,
                                              wf.parameterMeta,
                                              defaultAllowed = false)
                )
                .getOrElse(Vector.empty),
              getCallDocumentation(wf.body),
              wf.meta.map(getMetaDocumentation).getOrElse(Vector.empty),
              getDocumentationComment(wf)
          )
      }
      val tasks = sortedElements.collect {
        case task: Task =>
          TaskDocumentation(
              task.name,
              task.input
                .map(inp => getDeclarationDocumentation(inp.parameters, task.parameterMeta))
                .getOrElse(Vector.empty),
              task.output
                .map(inp =>
                  getDeclarationDocumentation(inp.parameters,
                                              task.parameterMeta,
                                              defaultAllowed = false)
                )
                .getOrElse(Vector.empty),
              task.runtime
                .map(
                    _.kvs.map(kv =>
                      KeyValueDocumentation(
                          kv.id,
                          getValueDocumentation(kv.expr, kv.loc.line),
                          getDocumentationComment(kv)
                      )
                    )
                )
                .getOrElse(Vector.empty),
              task.hints
                .map(
                    _.kvs.map(kv =>
                      KeyValueDocumentation(
                          kv.id,
                          getMetaValueDocumentation(kv.value, kv.loc.line),
                          getDocumentationComment(kv)
                      )
                    )
                )
                .getOrElse(Vector.empty),
              task.meta.map(getMetaDocumentation).getOrElse(Vector.empty),
              getDocumentationComment(task)
          )
      }

      // find the first element in the document and see if there's a top-level comment above it
      val firstElementLine = sortedElements.head.loc.line
      val topComments =
        doc.comments.filterWithin((doc.version.loc.endLine + 1) until firstElementLine)
      val overview = if (topComments.nonEmpty) {
        Some(DocumentationComment(topComments.toSortedVector))
      } else {
        None
      }

      Some(WdlDocumentation(doc.source, imports, structs, workflow.headOption, tasks, overview))
    } else {
      None
    }
  }

  def apply(docSource: FileNode,
            title: String,
            followImports: Boolean = true): Map[String, String] = {
    val docs =
      Parsers(followImports)
        .getDocumentWalker[Map[FileNode, WdlDocumentation]](docSource, Map.empty)
        .walk { (doc, results) =>
          val docs = generateDocumentation(doc)
          if (docs.isDefined) {
            results + (doc.source -> docs.get)
          } else {
            results
          }
        }
    // All structs share the same namespace so we put them on a separate page
    val structs = docs.values.flatMap(d => d.structs).toVector
    val renderer = Renderer()
    val pages = docs.map {
      case (source, doc) =>
        val destName = FileUtils.changeFileExt(source.name, ".wdl", ".md")
        destName -> renderer.render(DocumentTemplate, Map("doc" -> doc))
    }
    val structsPage = if (structs.nonEmpty) {
      Map(
          "structs.md" -> renderer.render(StructsTemplate, Map("structs" -> structs))
      )
    } else {
      Map.empty
    }
    val indexPage = Map(
        "index.md" -> renderer.render(IndexTemplate, Map("title" -> title, "pages" -> pages.keys))
    )
    pages ++ structsPage ++ indexPage
  }
}
