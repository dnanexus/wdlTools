package wdlTools.generators

import java.net.URL
import java.nio.file.Path

import wdlTools.formatter.V1_0Formatter
import wdlTools.generators.ProjectGenerator._
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Parsers, WdlExprParser, WdlTypeParser, WdlVersion}
import wdlTools.util.{InteractiveConsole, Options, Util}

import scala.collection.mutable
import util.control.Breaks._

case class ProjectGenerator(opts: Options,
                            name: String,
                            outputDir: Path,
                            wdlVersion: WdlVersion = WdlVersion.V1_0,
                            interactive: Boolean = false,
                            readmes: Boolean = false,
                            developerReadmes: Boolean = false,
                            dockerfile: Boolean = false,
                            tests: Boolean = false,
                            dockerImage: Option[String] = None,
                            generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty) {

  val defaultDockerImage = "debian:stretch-slim"
  lazy val formatter: V1_0Formatter = V1_0Formatter(opts)
  lazy val readmeGenerator: ReadmeGenerator =
    ReadmeGenerator(developerReadmes = developerReadmes, generatedFiles = generatedFiles)
  lazy val dockerfileGenerator: DockerfileGenerator =
    DockerfileGenerator(generatedFiles = generatedFiles)
  lazy val testsGenerator: TestsGenerator = TestsGenerator(generatedFiles = generatedFiles)
  lazy val console: InteractiveConsole = InteractiveConsole(promptColor = Console.BLUE)
  lazy val parsers: Parsers = Parsers(opts)
  lazy val typeParser: WdlTypeParser = parsers.getTypeParser(wdlVersion)
  lazy val exprParser: WdlExprParser = parsers.getExprParser(wdlVersion)

  val basicTypeChoices = Vector(
      "String",
      "Int",
      "Float",
      "Boolean",
      "File",
      "Array[String]",
      "Array[Int]",
      "Array[Float]",
      "Array[Boolean]",
      "Array[File]"
  )

  def containsFile(dataType: Type): Boolean = {
    dataType match {
      case _: TypeFile                  => true
      case TypeArray(t, _, _)           => containsFile(t)
      case TypeMap(k, v, _)             => containsFile(k) || containsFile(v)
      case TypePair(l, r, _)            => containsFile(l) || containsFile(r)
      case TypeStruct(_, members, _, _) => members.exists(x => containsFile(x.dataType))
      case _                            => false
    }
  }

  def requiresEvaluation(expr: Expr): Boolean = {
    expr match {
      case _: ValueString | _: ValueFile | _: ValueBoolean | _: ValueInt | _: ValueFloat => false
      case ExprPair(l, r, _)                                                             => requiresEvaluation(l) || requiresEvaluation(r)
      case ExprArray(value, _)                                                           => value.exists(requiresEvaluation)
      case ExprMap(value, _) =>
        value.exists(elt => requiresEvaluation(elt._1) || requiresEvaluation(elt._2))
      case ExprObject(value, _) => value.values.exists(requiresEvaluation)
      case _                    => true
    }
  }

  def readFields(fieldType: String,
                 choicesAllowed: Boolean,
                 fields: mutable.Buffer[FieldModel],
                 predefinedPrompt: Option[String] = None,
                 predefinedChoices: Vector[FieldModel] = Vector.empty): Unit = {
    lazy val predefinedChoiceMap: Map[String, FieldModel] =
      predefinedChoices.map(field => field.name -> field).toMap
    var continue: Boolean =
      console.askYesNo(prompt = s"Define ${fieldType.toLowerCase}s interactively?",
                       default = Some(true))
    var inputIdx: Int = 0
    while (continue) {
      inputIdx += 1
      console.title(s"${fieldType} ${inputIdx}")
      breakable {
        if (predefinedChoices.nonEmpty) {
          console.println(predefinedPrompt.get)
          val predefinedChoice = console.askOnce[String](
              prompt = "Select which workflow input",
              optional = true,
              choices = Some(predefinedChoiceMap.keys.toVector),
              menu = Some(true)
          )
          if (predefinedChoice.isDefined) {
            fields.append(predefinedChoiceMap(predefinedChoice.get).copy(linked = true))
            break
          }
        }
        val name = console.askRequired[String](prompt = "Name")
        val label = console.askOnce[String](prompt = "Label", optional = true)
        val help = console.askOnce[String](prompt = "Help", optional = true)
        val optional = console.askYesNo(prompt = "Optional", default = Some(false))
        val dataType: Type = typeParser.apply(
            console
              .askRequired[String](prompt = "Type",
                                   choices = Some(basicTypeChoices),
                                   otherOk = true)
        )
        val patterns = if (containsFile(dataType)) {
          console.ask[String](promptPrefix = "Patterns", optional = true, multiple = true)
        } else {
          Vector.empty
        }

        def askDefault: Option[Expr] = {
          console.askOnce[String](prompt = "Default", optional = true).map(exprParser.apply)
        }

        var default: Option[Expr] = askDefault
        while (default.isDefined && requiresEvaluation(default.get)) {
          console.error("Default value cannot be an expression that requires evaluation")
          default = askDefault
        }
        val choices = if (choicesAllowed) {
          def askChoices: Seq[Expr] = {
            console
              .ask[String](promptPrefix = "Choice", optional = true, multiple = true)
              .map(exprParser.apply)
          }

          var choiceList = askChoices
          while (choiceList.nonEmpty && choiceList.exists(requiresEvaluation)) {
            console.error("Choice value cannot be an expression that requires evaluation")
            choiceList = askChoices
          }
          choiceList
        } else {
          Vector.empty
        }
        fields.append(
            FieldModel(name, label, help, optional, dataType, patterns, default, choices)
        )
      }
      continue = console.askYesNo(prompt = s"Define another ${fieldType}?", default = Some(true))
    }
  }

  def populateWorkflow(model: WorkflowModel): Unit = {
    if (model.name.isEmpty) {
      model.name = console.askOnce[String](prompt = "Workflow name")
    }
    if (model.title.isEmpty) {
      model.title = console.askOnce[String](prompt = "Workflow title", optional = true)
    }
    if (model.summary.isEmpty) {
      model.summary = console.askOnce[String](prompt = "Workflow summary", optional = true)
    }
    if (model.description.isEmpty && !readmes) {
      model.description = console.askOnce[String](prompt = "Workflow description", optional = true)
    }
    readFields(fieldType = "Input", choicesAllowed = true, fields = model.inputs)
    readFields(fieldType = "Output", choicesAllowed = false, fields = model.outputs)
  }

  def populateTask(model: TaskModel = TaskModel(),
                   predefinedInputs: Vector[FieldModel] = Vector.empty): TaskModel = {
    if (model.name.isEmpty) {
      model.name = console.askOnce[String](prompt = "Task name")
    }
    if (model.title.isEmpty) {
      model.title = console.askOnce[String](prompt = "Task title", optional = true)
    }
    if (model.summary.isEmpty) {
      model.summary = console.askOnce[String](prompt = "Task summary", optional = true)
    }
    if (model.description.isEmpty && !readmes) {
      model.description = console.askOnce[String](prompt = "Task description", optional = true)
    }
    if (model.docker.isEmpty) {
      model.docker =
        console.askOnce[String](prompt = "Docker image ID", default = Some(defaultDockerImage))
    }
    readFields(fieldType = "Input",
               choicesAllowed = true,
               fields = model.inputs,
               predefinedPrompt = Some("Is this a workflow input?"),
               predefinedChoices = predefinedInputs)
    readFields(fieldType = "Output", choicesAllowed = false, fields = model.outputs)
    model
  }

  def apply(workflowModel: Option[WorkflowModel], taskModels: Vector[TaskModel]): Unit = {
    val tasksAndLinkedInputs = if (interactive) {
      val predefinedTaskInputs = if (workflowModel.isDefined) {
        populateWorkflow(workflowModel.get)
        workflowModel.get.inputs.toVector
      } else {
        Vector.empty
      }
      val tasksBuf: mutable.Buffer[(Task, Set[String])] = mutable.ArrayBuffer.empty
      taskModels.foreach { taskModel =>
        tasksBuf.append(populateTask(taskModel, predefinedInputs = predefinedTaskInputs).toTask)
      }
      var word = if (taskModels.isEmpty) {
        "a"
      } else {
        "another"
      }
      while (console.askYesNo(s"Add ${word} task?", default = Some(false))) {
        tasksBuf.append(populateTask(predefinedInputs = predefinedTaskInputs).toTask)
        word = "another"
      }
      tasksBuf.toVector
    } else {
      taskModels.map(_.toTask)
    }

    val doc = Document(wdlVersion,
                       null,
                       tasksAndLinkedInputs.map(_._1),
                       workflowModel.map(_.toWorkflow(tasksAndLinkedInputs)),
                       null,
                       None)
    val wdlName = s"${name}.wdl"
    val docUrl = Util.getURL(outputDir.resolve(wdlName))
    generatedFiles(docUrl) = formatter.formatDocument(doc).mkString(System.lineSeparator())

    if (readmes) {
      readmeGenerator.apply(docUrl, doc)
    }

    if (dockerfile) {
      val dockerfileUrl = Util.getURL(outputDir.resolve("Dockerfile"))
      dockerfileGenerator.apply(dockerfileUrl)
    }

    if (tests) {
      val testUrl = Util.getURL(outputDir.resolve("tests").resolve(s"test_${name}.json"))
      testsGenerator.apply(testUrl, wdlName, doc)
    }
  }
}

object ProjectGenerator {
  case class FieldModel(name: String,
                        label: Option[String] = None,
                        help: Option[String] = None,
                        optional: Boolean,
                        dataType: Type,
                        patterns: Seq[String] = Vector.empty,
                        default: Option[Expr] = None,
                        choices: Seq[Expr] = Vector.empty,
                        linked: Boolean = false) {
    def toDeclaration: Declaration = {
      Declaration(name, dataType, None, null, None)
    }

    def toMeta: Option[MetaKV] = {
      val metaMap: Map[String, Expr] = Map(
          "label" -> label.map(ValueString(_, null)),
          "help" -> help.map(ValueString(_, null)),
          "patterns" -> (if (patterns.isEmpty) {
                           None
                         } else {
                           Some(ExprArray(patterns.map(ValueString(_, null)).toVector, null))
                         }),
          "default" -> default,
          "choices" -> (if (choices.isEmpty) {
                          None
                        } else {
                          Some(ExprArray(choices.toVector, null))
                        })
      ).collect {
        case (key, Some(value)) => key -> value
      }
      if (metaMap.isEmpty) {
        None
      } else {
        Some(MetaKV(name, ExprObject(metaMap, null), null, None))
      }
    }
  }

  def getInput(inputs: Vector[FieldModel]): (Option[InputSection], Set[String]) = {
    if (inputs.isEmpty) {
      (None, Set.empty)
    } else {
      val inputSection = InputSection(inputs.map(_.toDeclaration), null, None)
      val linkedInputs = inputs.collect {
        case f: FieldModel if f.linked => f.name
      }.toSet
      (Some(inputSection), linkedInputs)
    }
  }

  def getOutput(outputs: Vector[FieldModel]): Option[OutputSection] = {
    if (outputs.isEmpty) {
      None
    } else {
      Some(OutputSection(outputs.map(_.toDeclaration), null, None))
    }
  }

  def getMeta(items: Map[String, String]): Option[MetaSection] = {
    if (items.isEmpty) {
      None
    } else {
      Some(
          MetaSection(items.map {
            case (key, value) => MetaKV(key, ValueString(value, null), null, None)
          }.toVector, null, None)
      )
    }
  }

  def getParameterMeta(inputs: Vector[FieldModel]): Option[ParameterMetaSection] = {
    if (inputs.isEmpty) {
      None
    } else {
      val inputMetaKVs = inputs.flatMap(_.toMeta)
      if (inputMetaKVs.isEmpty) {
        None
      } else {
        Some(ParameterMetaSection(inputMetaKVs, null, None))
      }
    }
  }

  case class TaskModel(var name: Option[String] = None,
                       var title: Option[String] = None,
                       var summary: Option[String] = None,
                       var description: Option[String] = None,
                       var docker: Option[String] = None,
                       inputs: mutable.Buffer[FieldModel] = mutable.ArrayBuffer.empty,
                       outputs: mutable.Buffer[FieldModel] = mutable.ArrayBuffer.empty) {
    def toTask: (Task, Set[String]) = {
      val (inputSection, linkedInputs) = getInput(inputs.toVector)
      val task = Task(
          name.get,
          inputSection,
          getOutput(outputs.toVector),
          CommandSection(Vector.empty, null, None),
          Vector.empty,
          getMeta(
              Map("title" -> title, "summary" -> summary, description -> "description").collect {
                case (key: String, Some(value: String)) => key -> value
              }
          ),
          getParameterMeta(inputs.toVector),
          Some(
              RuntimeSection(Vector(RuntimeKV("docker", ValueString(docker.get, null), null, None)),
                             null,
                             None)
          ),
          null,
          None
      )
      (task, linkedInputs)
    }
  }

  case class WorkflowModel(wdlVersion: WdlVersion,
                           var name: Option[String] = None,
                           var title: Option[String] = None,
                           var summary: Option[String] = None,
                           var description: Option[String] = None,
                           inputs: mutable.Buffer[FieldModel] = mutable.ArrayBuffer.empty,
                           outputs: mutable.Buffer[FieldModel] = mutable.ArrayBuffer.empty) {
    def toWorkflow(tasksAndLinkedInputs: Vector[(Task, Set[String])]): Workflow = {
      val calls: Vector[Call] = tasksAndLinkedInputs.map {
        case (task, linkedInputs) =>
          val callInputs: Map[String, Expr] = if (task.input.isDefined) {
            def getInputValue(inp: Declaration): Option[(String, Expr)] = {
              if (linkedInputs.contains(inp.name)) {
                Some(inp.name -> ExprIdentifier(inp.name, null))
              } else if (inp.wdlType.isInstanceOf[TypeOptional]) {
                None
              } else {
                Some(inp.name -> ValueString("set my value!", null))
              }
            }
            task.input.get.declarations.flatMap(getInputValue).toMap
          } else {
            Map.empty
          }
          Call(task.name, None, callInputs, null, None)
      }

      val (wfInputSection, _) = getInput(inputs.toVector)
      Workflow(
          name.get,
          wfInputSection,
          getOutput(outputs.toVector),
          getMeta(
              Map("title" -> title, "summary" -> summary, description -> "description").collect {
                case (key: String, Some(value: String)) => key -> value
              }
          ),
          getParameterMeta(inputs.toVector),
          calls,
          null,
          None
      )
    }
  }
}
