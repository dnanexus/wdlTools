package wdlTools.generators

import java.net.URI

import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.URL

import scala.collection.mutable

case class ReadmeGenerator(url: URL,
                           document: Document,
                           developerReadmes: Boolean = false,
                           renderer: Renderer = SspRenderer(),
                           readmes: mutable.Map[URL, String] = mutable.HashMap.empty) {

  val WORKFLOW_README_TEMPLATE = "/templates/WorkflowReadme.md.ssp"
  val TASK_README_TEMPLATE = "/templates/TaskReadme.md.ssp"
  val WORKFLOW_README_DEVELOPER_TEMPLATE = "/templates/WorkflowReadme.developer.md.ssp"
  val TASK_README_DEVELOPER_TEMPLATE = "/templates/TaskReadme.developer.md.ssp"

  private val uri: URI = new URI(url.addr)
  private val pathParts: Seq[String] = uri.getPath.split("/")
  require(pathParts.last.endsWith(".wdl"))
  private val wdlName: String = pathParts.last.slice(0, pathParts.last.length - 4)

  def getReadmeNameAndURL(elementName: String, developer: Boolean): (String, URL) = {
    val devStr = if (developer) {
      "developer."
    } else {
      ""
    }
    val newName = s"Readme.${devStr}${wdlName}.${elementName}.md"
    val newPath = (pathParts.slice(0, pathParts.size - 1) ++ Vector(newName)).mkString("/")
    val newURI = new URI(uri.getScheme,
                         uri.getUserInfo,
                         uri.getHost,
                         uri.getPort,
                         newPath,
                         uri.getQuery,
                         uri.getFragment)
    (newName, URL.fromUri(newURI))
  }

  def generateWorkflowReadme(workflow: Workflow,
                             tasks: Seq[(String, String)],
                             developer: Boolean): Unit = {
    val (_, url) = getReadmeNameAndURL(workflow.name, developer = developer)
    val templateName = if (developer) {
      WORKFLOW_README_DEVELOPER_TEMPLATE
    } else {
      WORKFLOW_README_TEMPLATE
    }
    val contents = renderer.render(templateName, Map("workflow" -> workflow, "tasks" -> tasks))
    readmes(url) = contents
  }

  def generateTaskReadme(task: Task, developer: Boolean): String = {
    val (readmeName, url) = getReadmeNameAndURL(task.name, developer = developer)
    val templateName = if (developer) {
      TASK_README_DEVELOPER_TEMPLATE
    } else {
      TASK_README_TEMPLATE
    }
    val contents = renderer.render(templateName, Map("task" -> task))
    readmes(url) = contents
    readmeName
  }

  def apply(): Unit = {
    val tasks = document.elements.flatMap {
      case task: Task =>
        val taskReadme = generateTaskReadme(task, developer = false)
        if (developerReadmes) {
          generateTaskReadme(task, developer = true)
        }
        Some(task.name -> taskReadme)
      case _ => None
    }

    if (document.workflow.isDefined) {
      generateWorkflowReadme(document.workflow.get, tasks, developer = false)
      if (developerReadmes) {
        generateWorkflowReadme(document.workflow.get, tasks, developer = true)
      }
    }
  }
}
