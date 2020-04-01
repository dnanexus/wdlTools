package wdlTools.generators

import java.net.URI

import wdlTools.syntax.AbstractSyntax._

import scala.collection.mutable

case class ReadmeGenerator(uri: URI,
                           document: Document,
                           developerReadmes: Boolean = false,
                           renderer: Renderer = SspRenderer(),
                           readmes: mutable.Map[URI, String] = mutable.HashMap.empty) {

  val WORKFLOW_README_TEMPLATE = "/templates/WorkflowReadme.md.ssp"
  val TASK_README_TEMPLATE = "/templates/TaskReadme.md.ssp"
  val WORKFLOW_README_DEVELOPER_TEMPLATE = "/templates/WorkflowReadme.developer.md.ssp"
  val TASK_README_DEVELOPER_TEMPLATE = "/templates/TaskReadme.developer.md.ssp"

  val parts: Seq[String] = uri.getPath.split("/")
  require(parts.last.endsWith(".wdl"))
  val wdlName: String = parts.last.slice(0, parts.last.length - 4)

  def getURI(elementName: String, developer: Boolean): (URI, String) = {
    val devStr = if (developer) {
      "developer."
    } else {
      ""
    }
    val newName = s"Readme.${devStr}${wdlName}.${elementName}.md"
    val newPath = (parts.slice(0, parts.size - 1) ++ Vector(newName)).mkString("/")
    val newURI = new URI(uri.getScheme,
                         uri.getUserInfo,
                         uri.getHost,
                         uri.getPort,
                         newPath,
                         uri.getQuery,
                         uri.getFragment)
    (newURI, newName)
  }

  def generateWorkflowReadme(workflow: Workflow,
                             tasks: Seq[(String, String)],
                             developer: Boolean): Unit = {
    val (uri, _) = getURI(workflow.name, developer = developer)
    val templateName = if (developer) {
      WORKFLOW_README_DEVELOPER_TEMPLATE
    } else {
      WORKFLOW_README_TEMPLATE
    }
    val contents = renderer.render(templateName, Map("workflow" -> workflow, "tasks" -> tasks))
    readmes(uri) = contents
  }

  def generateTaskReadme(task: Task, developer: Boolean): String = {
    val (uri, readmeName) = getURI(task.name, developer = developer)
    val templateName = if (developer) {
      TASK_README_DEVELOPER_TEMPLATE
    } else {
      TASK_README_TEMPLATE
    }
    val contents = renderer.render(templateName, Map("task" -> task))
    readmes(uri) = contents
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
