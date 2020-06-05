package wdlTools.generators

import java.net.URL

import wdlTools.generators.DocumentationGenerator._
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.Util.exprToString
import wdlTools.syntax.{Comment, Parsers}
import wdlTools.util.{Options, Util}

object DocumentationGenerator {
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

  case class ImportDocumentation(addr: String,
                                 name: String,
                                 aliases: Map[String, String],
                                 comment: Option[DocumentationComment])

  trait ValueDocumentation {
    val comment: Option[DocumentationComment]
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
                                      wdlType: Type,
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

  case class WdlDocumentation(sourceUrl: Option[URL],
                              imports: Vector[ImportDocumentation],
                              structs: Vector[StructDocumentation],
                              workflow: Option[WorkflowDocumentation],
                              tasks: Vector[TaskDocumentation],
                              comment: Option[DocumentationComment])

}

case class DocumentationGenerator(opts: Options) {
  private val DOCUMENT_TEMPLATE = "/templates/documentation/document.ssp"
  private val STRUCTS_TEMPLATE = "/templates/documentation/structs.ssp"
  private val INDEX_TEMPLATE = "/templates/documentation/index.ssp"

  def generateDocumentation(doc: Document): Option[WdlDocumentation] = {
    val sortedElements = (doc.elements ++ doc.workflow.map(Vector(_)).getOrElse(Vector.empty))
      .sortWith(_.text < _.text)
    if (sortedElements.nonEmpty) {
      def getDocumentationComment(element: Element): Option[DocumentationComment] = {
        val preceedingComments =
          (1 until element.text.line).reverse
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
        val comment = if (value.text.line > parentLine) {
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
        val comment = if (value.text.line > parentLine) {
          getDocumentationComment(value)
        } else {
          None
        }
        value match {
          case ExprObject(value, text) =>
            MapValueDocumentation(
                value.map(v => v.key -> getValueDocumentation(v.value, text.line)).toMap,
                comment
            )
          case ExprMap(value, text) =>
            MapValueDocumentation(
                value
                  .map(v => exprToString(v.key) -> getValueDocumentation(v.value, text.line))
                  .toMap,
                comment
            )
          case ExprArray(value, text) =>
            ListValueDocumentation(value.map(v => getValueDocumentation(v, text.line)), comment)
          case ExprPair(left, right, text) =>
            ListValueDocumentation(Vector(
                                       getValueDocumentation(left, text.line),
                                       getValueDocumentation(right, text.line)
                                   ),
                                   comment)
          case other => SimpleValueDocumentation(other, comment)
        }
      }

      def getMetaDocumentation(meta: MetaSection): Vector[KeyValueDocumentation] = {
        meta.kvs.map { kv =>
          KeyValueDocumentation(kv.id,
                                getMetaValueDocumentation(kv.value, kv.text.line),
                                getDocumentationComment(kv))
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
            d.expr.map(e => exprToString(e))
          } else {
            None
          }
          DeclarationDocumentation(
              d.name,
              d.wdlType,
              default,
              paramMeta.map(v => getMetaValueDocumentation(v, meta.get.text.line)),
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
                .getOrElse(Util.getFilename(imp.addr.value, ".wdl")),
              imp.aliases.map(a => a.id1 -> a.id2).toMap,
              getDocumentationComment(imp)
          )
      }
      val structs = sortedElements.collect {
        case struct: TypeStruct =>
          StructDocumentation(struct.name, struct.members.map(m => {
            DeclarationDocumentation(m.name, m.wdlType, comment = getDocumentationComment(m))
          }), getDocumentationComment(struct))
      }
      val workflow = sortedElements.collect {
        case wf: Workflow =>
          WorkflowDocumentation(
              wf.name,
              wf.input
                .map(inp => getDeclarationDocumentation(inp.declarations, wf.parameterMeta))
                .getOrElse(Vector.empty),
              wf.output
                .map(inp =>
                  getDeclarationDocumentation(inp.declarations,
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
                .map(inp => getDeclarationDocumentation(inp.declarations, task.parameterMeta))
                .getOrElse(Vector.empty),
              task.output
                .map(inp =>
                  getDeclarationDocumentation(inp.declarations,
                                              task.parameterMeta,
                                              defaultAllowed = false)
                )
                .getOrElse(Vector.empty),
              task.runtime
                .map(
                    _.kvs.map(kv =>
                      KeyValueDocumentation(
                          kv.id,
                          getValueDocumentation(kv.expr, kv.text.line),
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
                          getMetaValueDocumentation(kv.value, kv.text.line),
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
      val firstElementLine = sortedElements.head.text.line
      val topComments =
        doc.comments.filterWithin((doc.version.text.endLine + 1) until firstElementLine)
      val overview = if (topComments.nonEmpty) {
        Some(DocumentationComment(topComments.toSortedVector))
      } else {
        None
      }

      Some(WdlDocumentation(doc.sourceUrl, imports, structs, workflow.headOption, tasks, overview))
    } else {
      None
    }
  }

  def apply(url: URL, title: String): Map[String, String] = {
    val docs = Parsers(opts).getDocumentWalker[Map[URL, WdlDocumentation]](url, Map.empty).walk {
      (doc, results) =>
        val docs = generateDocumentation(doc)
        if (docs.isDefined) {
          results + (doc.sourceUrl.get -> docs.get)
        } else {
          results
        }
    }
    // All structs share the same namespace so we put them on a separate page
    val structs = docs.values.flatMap(d => d.structs).toVector
    val renderer: Renderer = Renderer()
    val pages = docs.map {
      case (url, doc) =>
        Util.getFilename(url.getPath, ".wdl", ".md") ->
          renderer.render(DOCUMENT_TEMPLATE, Map("doc" -> doc))
    } ++ (if (structs.nonEmpty) {
            Map("structs.md" -> renderer.render(STRUCTS_TEMPLATE, Map("structs" -> structs)))
          } else {
            Map.empty
          })
    pages ++ Map(
        "index.md" ->
          renderer.render(INDEX_TEMPLATE, Map("title" -> title, "pages" -> pages.keys.toVector))
    )
  }
}
