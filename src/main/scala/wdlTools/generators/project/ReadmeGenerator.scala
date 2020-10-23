package wdlTools.generators.project

import java.nio.file.Path

import wdlTools.generators.Renderer
import wdlTools.syntax.AbstractSyntax.{Document, Task, Workflow}
import dx.util.{LocalFileSource, FileUtils}

case class ReadmeGenerator(developerReadmes: Boolean = false, renderer: Renderer = Renderer()) {
  val WORKFLOW_README_TEMPLATE = "/templates/readme/WorkflowReadme.md.ssp"
  val TASK_README_TEMPLATE = "/templates/readme/TaskReadme.md.ssp"
  val WORKFLOW_README_DEVELOPER_TEMPLATE = "/templates/readme/WorkflowReadme.developer.md.ssp"
  val TASK_README_DEVELOPER_TEMPLATE = "/templates/readme/TaskReadme.developer.md.ssp"

  case class Generator(wdlSource: LocalFileSource) {
    private val wdlPath = wdlSource.canonicalPath
    private val fname = wdlPath.getFileName.toString
    require(fname.endsWith(".wdl"))
    private val wdlName = fname.slice(0, fname.length - 4)
    private var generatedFiles: Map[Path, String] = Map.empty

    def getGeneratedFiles: Map[Path, String] = generatedFiles

    def getReadmeNameAndPath(elementName: String, developer: Boolean): (String, Path) = {
      val devStr = if (developer) {
        "developer."
      } else {
        ""
      }
      val newName = s"Readme.${devStr}${wdlName}.${elementName}.md"
      val newPath = FileUtils.absolutePath(wdlPath.getParent.resolve(newName))
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
      generatedFiles += (path -> contents)
    }

    def generateTaskReadme(task: Task, developer: Boolean): String = {
      val (readmeName, path) = getReadmeNameAndPath(task.name, developer = developer)
      val templateName = if (developer) {
        TASK_README_DEVELOPER_TEMPLATE
      } else {
        TASK_README_TEMPLATE
      }
      val contents = renderer.render(templateName, Map("task" -> task))
      generatedFiles += (path -> contents)
      readmeName
    }
  }

  def apply(document: Document): Map[Path, String] = {
    val localFileSource = document.source match {
      case lfs: LocalFileSource => lfs
      case _ =>
        throw new RuntimeException(s"Cannot generate READMEs for non-local file ${document.source}")
    }
    val generator = Generator(localFileSource)

    val tasks: Seq[(String, String)] = document.elements.flatMap {
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

    generator.getGeneratedFiles
  }
}
