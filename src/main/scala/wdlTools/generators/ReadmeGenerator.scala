package wdlTools.generators

import java.net.URL
import java.nio.file.Path

import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.Util

import scala.collection.mutable

case class ReadmeGenerator(developerReadmes: Boolean = false,
                           renderer: Renderer = SspRenderer(),
                           readmes: mutable.Map[URL, String] = mutable.HashMap.empty) {

  val WORKFLOW_README_TEMPLATE = "/templates/WorkflowReadme.md.ssp"
  val TASK_README_TEMPLATE = "/templates/TaskReadme.md.ssp"
  val WORKFLOW_README_DEVELOPER_TEMPLATE = "/templates/WorkflowReadme.developer.md.ssp"
  val TASK_README_DEVELOPER_TEMPLATE = "/templates/TaskReadme.developer.md.ssp"

  case class Generator(wdlUrl: URL) {
    private val wdlPath = Util.getLocalPath(wdlUrl)
    private val fname = wdlPath.getFileName.toString
    require(fname.endsWith(".wdl"))
    private val wdlName = fname.slice(0, fname.length - 4)

    def getReadmeNameAndPath(elementName: String, developer: Boolean): (String, Path) = {
      val devStr = if (developer) {
        "developer."
      } else {
        ""
      }
      val newName = s"Readme.${devStr}${wdlName}.${elementName}.md"
      val newPath = wdlPath.getParent.resolve(newName)
      (newName, newPath)
    }

    def generateWorkflowReadme(workflow: Workflow,
                               tasks: Seq[(String, String)],
                               developer: Boolean): Unit = {
      val (_, path) = getReadmeNameAndPath(workflow.name, developer = developer)
      val templateName = if (developer) {
        WORKFLOW_README_DEVELOPER_TEMPLATE
      } else {
        WORKFLOW_README_TEMPLATE
      }
      val contents = renderer.render(templateName, Map("workflow" -> workflow, "tasks" -> tasks))
      readmes(path) = contents
    }

    def generateTaskReadme(task: Task, developer: Boolean): String = {
      val (readmeName, path) = getReadmeNameAndPath(task.name, developer = developer)
      val templateName = if (developer) {
        TASK_README_DEVELOPER_TEMPLATE
      } else {
        TASK_README_TEMPLATE
      }
      val contents = renderer.render(templateName, Map("task" -> task))
      readmes(path) = contents
      readmeName
    }
  }

  def apply(wdlUrl: URL, document: Document): Unit = {
    val generator = Generator(wdlUrl)

    val tasks = document.elements.flatMap {
      case task: Task =>
        val taskReadme = generator.generateTaskReadme(task, developer = false)
        if (developerReadmes) {
          generator.generateTaskReadme(task, developer = true)
        }
        Some(task.name -> taskReadme)
      case _ => None
    }

    if (document.workflow.isDefined) {
      generator.generateWorkflowReadme(document.workflow.get, tasks, developer = false)
      if (developerReadmes) {
        generator.generateWorkflowReadme(document.workflow.get, tasks, developer = true)
      }
    }
  }
}
