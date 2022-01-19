package wdlTools.generators.project

import wdlTools.generators.{Renderer, code}
import wdlTools.generators.code.WdlFormatter
import wdlTools.generators.project.ProjectGenerator.{FieldModel, TaskModel, WorkflowModel}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{CommentMap, Parsers, SourceLocation, WdlParser, WdlVersion}
import dx.util.{FileUtils, InteractiveConsole}

import scala.util.control.Breaks._

case class ProjectGenerator(name: String,
                            wdlVersion: WdlVersion = WdlVersion.V1,
                            interactive: Boolean = false,
                            readmes: Boolean = false,
                            developerReadmes: Boolean = false,
                            dockerfile: Boolean = false,
                            tests: Boolean = false,
                            makefile: Boolean = true,
                            dockerImage: Option[String] = None,
                            followImports: Boolean = true) {

  val DOCKERFILE_TEMPLATE = "/templates/project/Dockerfile.ssp"
  val MAKEFILE_TEMPLATE = "/templates/project/Makefile.ssp"

  val defaultDockerImage = "debian:stretch-slim"
  lazy val formatter: WdlFormatter =
    code.WdlFormatter(Some(wdlVersion), followImports = followImports)
  lazy val renderer: Renderer = Renderer()
  lazy val readmeGenerator: ReadmeGenerator =
    ReadmeGenerator(developerReadmes = developerReadmes, renderer = renderer)
  lazy val console: InteractiveConsole = InteractiveConsole(promptColor = Console.BLUE)
  lazy val parsers: Parsers = Parsers(followImports)
  lazy val fragParser: WdlParser = parsers.getParser(wdlVersion)

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
      case _: TypeFile            => true
      case TypeArray(t, _)        => containsFile(t)
      case TypeMap(k, v)          => containsFile(k) || containsFile(v)
      case TypePair(l, r)         => containsFile(l) || containsFile(r)
      case TypeStruct(_, members) => members.exists(x => containsFile(x.wdlType))
      case _                      => false
    }
  }

  def requiresEvaluation(expr: Expr): Boolean = {
    expr match {
      case _: ValueString | _: ValueBoolean | _: ValueInt | _: ValueFloat => false
      case ExprPair(l, r)   => requiresEvaluation(l) || requiresEvaluation(r)
      case ExprArray(value) => value.exists(requiresEvaluation)
      case ExprMap(value) =>
        value.exists(elt => requiresEvaluation(elt.key) || requiresEvaluation(elt.value))
      case ExprObject(members)    => members.exists(member => requiresEvaluation(member.value))
      case ExprStruct(_, members) => members.exists(member => requiresEvaluation(member.value))
      case _                      => true
    }
  }

  def exprToMetaValue(expr: Expr): MetaValue = {
    if (requiresEvaluation(expr)) {
      throw new Exception("Cannot use an expression that requires evaluation")
    }
    expr match {
      case ValueString(value, quoting) => MetaValueString(value, quoting)(expr.loc)
      case ValueInt(value)             => MetaValueInt(value)(expr.loc)
      case ValueFloat(value)           => MetaValueFloat(value)(expr.loc)
      case ValueBoolean(value)         => MetaValueBoolean(value)(expr.loc)
      case ExprArray(value)            => MetaValueArray(value.map(exprToMetaValue))(expr.loc)
      case ExprObject(value) =>
        MetaValueObject(value.map {
          case ExprMember(key, value) =>
            val keyStr = exprToMetaValue(key) match {
              case MetaValueString(s, _) => s
              case _                     => throw new Exception(s"Invalid meta object key ${key}")
            }
            MetaKV(keyStr, exprToMetaValue(value))(expr.loc)
        })(
            expr.loc
        )
      case other => throw new Exception(s"Invalid meta value ${other}")
    }
  }

  def readFields(fieldType: String,
                 choicesAllowed: Boolean,
                 startFields: Vector[FieldModel],
                 predefinedPrompt: Option[String] = None,
                 predefinedChoices: Vector[FieldModel] = Vector.empty): Unit = {
    lazy val predefinedChoiceMap: Map[String, FieldModel] =
      predefinedChoices.map(field => field.name -> field).toMap
    var fields = startFields
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
            fields :+= predefinedChoiceMap(predefinedChoice.get).copy(linked = true)
            break()
          }
        }
        val name = console.askRequired[String](prompt = "Name")
        val label = console.askOnce[String](prompt = "Label", optional = true)
        val help = console.askOnce[String](prompt = "Help", optional = true)
        val optional = console.askYesNo(prompt = "Optional", default = Some(false))
        val dataType: Type = fragParser.parseType(
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

        def askDefault: Either[String, Option[MetaValue]] = {
          try {
            Right(
                console
                  .askOnce[String](prompt = "Default", optional = true)
                  .map(x => fragParser.parseExpr(x))
                  .map(exprToMetaValue)
            )
          } catch {
            case t: Throwable => Left(t.getMessage)
          }
        }

        var defaultOrError: Either[String, Option[MetaValue]] = askDefault
        while (defaultOrError.isLeft) {
          defaultOrError match {
            case Left(err) =>
              console.error(err)
              defaultOrError = askDefault
            case _ => throw new RuntimeException()
          }
        }
        val choices = if (choicesAllowed) {
          def askChoices: Either[String, Seq[MetaValue]] = {
            try {
              Right(
                  console
                    .ask[String](promptPrefix = "Choice", optional = true, multiple = true)
                    .map(fragParser.parseExpr)
                    .map(exprToMetaValue)
              )
            } catch {
              case t: Throwable => Left(t.getMessage)
            }
          }

          var choiceListOrError = askChoices
          while (choiceListOrError.isLeft) {
            choiceListOrError match {
              case Left(err) =>
                console.error(err)
                choiceListOrError = askChoices
              case _ => throw new RuntimeException()
            }
          }
          choiceListOrError match {
            case Right(x) => x
            case _        => throw new RuntimeException()
          }
        } else {
          Vector.empty
        }
        fields :+= FieldModel(
            name,
            label,
            help,
            optional,
            dataType,
            patterns,
            defaultOrError match {
              case Right(default) => default
              case _              => throw new RuntimeException()
            },
            choices
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
    readFields(fieldType = "Input", choicesAllowed = true, startFields = model.inputs)
    readFields(fieldType = "Output", choicesAllowed = false, startFields = model.outputs)
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
               startFields = model.inputs,
               predefinedPrompt = Some("Is this a workflow input?"),
               predefinedChoices = predefinedInputs)
    readFields(fieldType = "Output", choicesAllowed = false, startFields = model.outputs)
    model
  }

  def apply(workflowModel: Option[WorkflowModel],
            taskModels: Vector[TaskModel]): Map[String, String] = {
    val tasksAndLinkedInputs = if (interactive) {
      val predefinedTaskInputs = if (workflowModel.isDefined) {
        populateWorkflow(workflowModel.get)
        workflowModel.get.inputs
      } else {
        Vector.empty
      }
      var tasks: Vector[(Task, Set[String])] = taskModels.map { taskModel =>
        populateTask(taskModel, predefinedInputs = predefinedTaskInputs).toTask
      }
      var word = if (taskModels.isEmpty) {
        "a"
      } else {
        "another"
      }
      while (console.askYesNo(s"Add ${word} task?", default = Some(false))) {
        tasks :+= populateTask(predefinedInputs = predefinedTaskInputs).toTask
        word = "another"
      }
      tasks
    } else {
      taskModels.map(_.toTask)
    }

    val doc = Document(null,
                       Version(wdlVersion)(SourceLocation.empty),
                       tasksAndLinkedInputs.map(_._1),
                       workflowModel.map(_.toWorkflow(tasksAndLinkedInputs)),
                       CommentMap.empty)(SourceLocation.empty)
    val wdlName = s"${name}.wdl"
    val wdlFile = Map(
        wdlName -> FileUtils.linesToString(formatter.formatDocument(doc), trailingNewline = true)
    )
    val readMes = if (readmes) {
      readmeGenerator.apply(doc).map {
        case (path, content) => path.getFileName.toString -> content
      }
    } else {
      Map.empty
    }
    val dockerFile = if (dockerfile) {
      Map("Dockerfile" -> renderer.render(DOCKERFILE_TEMPLATE))
    } else {
      Map.empty
    }
    val testFile = if (tests) {
      val testPath = FileUtils.getPath("tests").resolve(s"test_${name}.json").toString
      Map(testPath -> TestsGenerator.apply(wdlName, doc))
    } else {
      Map.empty
    }
    val makeFile = if (makefile) {
      Map(
          "Makefile" ->
            renderer.render(
                MAKEFILE_TEMPLATE,
                Map("name" -> name, "test" -> tests, "docker" -> dockerfile)
            )
      )
    } else {
      Map.empty
    }
    wdlFile ++ readMes ++ dockerFile ++ testFile ++ makeFile
  }
}

object ProjectGenerator {
  case class FieldModel(name: String,
                        label: Option[String] = None,
                        help: Option[String] = None,
                        optional: Boolean,
                        dataType: Type,
                        patterns: Seq[String] = Vector.empty,
                        default: Option[MetaValue] = None,
                        choices: Seq[MetaValue] = Vector.empty,
                        linked: Boolean = false) {
    def toDeclaration: Declaration = {
      Declaration(name, dataType, None)(SourceLocation.empty)
    }

    def toMeta: Option[MetaKV] = {
      val metaMap: Vector[MetaKV] = Map(
          "label" -> label.map(MetaValueString(_)(SourceLocation.empty)),
          "help" -> help.map(MetaValueString(_)(SourceLocation.empty)),
          "patterns" -> (if (patterns.isEmpty) {
                           None
                         } else {
                           Some(
                               MetaValueArray(
                                   patterns.map(MetaValueString(_)(SourceLocation.empty)).toVector
                               )(SourceLocation.empty)
                           )
                         }),
          "default" -> default,
          "choices" -> (if (choices.isEmpty) {
                          None
                        } else {
                          Some(MetaValueArray(choices.toVector)(SourceLocation.empty))
                        })
      ).collect {
        case (key, Some(value)) => key -> value
      }.map(item => MetaKV(item._1, item._2)(SourceLocation.empty))
        .toVector
      if (metaMap.isEmpty) {
        None
      } else {
        Some(MetaKV(name, MetaValueObject(metaMap)(SourceLocation.empty))(SourceLocation.empty))
      }
    }
  }

  def getInput(inputs: Vector[FieldModel]): (Option[InputSection], Set[String]) = {
    if (inputs.isEmpty) {
      (None, Set.empty)
    } else {
      val inputSection = InputSection(inputs.map(_.toDeclaration))(SourceLocation.empty)
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
      Some(OutputSection(outputs.map(_.toDeclaration))(SourceLocation.empty))
    }
  }

  def getMeta(items: Map[String, String]): Option[MetaSection] = {
    if (items.isEmpty) {
      None
    } else {
      Some(
          MetaSection(items.map {
            case (key, value) =>
              MetaKV(key, MetaValueString(value)(SourceLocation.empty))(SourceLocation.empty)
          }.toVector)(SourceLocation.empty)
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
        Some(ParameterMetaSection(inputMetaKVs)(SourceLocation.empty))
      }
    }
  }

  case class TaskModel(var name: Option[String] = None,
                       var title: Option[String] = None,
                       var summary: Option[String] = None,
                       var description: Option[String] = None,
                       var docker: Option[String] = None,
                       inputs: Vector[FieldModel] = Vector.empty,
                       outputs: Vector[FieldModel] = Vector.empty) {
    def toTask: (Task, Set[String]) = {
      val (inputSection, linkedInputs) = getInput(inputs)
      val task = Task(
          name.get,
          inputSection,
          getOutput(outputs),
          CommandSection(Vector.empty)(SourceLocation.empty),
          Vector.empty,
          getMeta(
              Map("title" -> title, "summary" -> summary, description -> "description").collect {
                case (key: String, Some(value: String)) => key -> value
              }
          ),
          getParameterMeta(inputs),
          Some(
              RuntimeSection(
                  Vector(
                      RuntimeKV("docker", ValueString(docker.get)(SourceLocation.empty))(
                          SourceLocation.empty
                      )
                  )
              )(SourceLocation.empty)
          ),
          None
      )(SourceLocation.empty)
      (task, linkedInputs)
    }
  }

  case class WorkflowModel(wdlVersion: WdlVersion,
                           var name: Option[String] = None,
                           var title: Option[String] = None,
                           var summary: Option[String] = None,
                           var description: Option[String] = None,
                           inputs: Vector[FieldModel] = Vector.empty,
                           outputs: Vector[FieldModel] = Vector.empty) {
    def toWorkflow(tasksAndLinkedInputs: Vector[(Task, Set[String])]): Workflow = {
      val calls: Vector[Call] = tasksAndLinkedInputs.map {
        case (task, linkedInputs) =>
          val callInputs: Option[CallInputs] = if (task.input.isDefined) {
            def getInputValue(inp: Declaration): Option[CallInput] = {
              if (linkedInputs.contains(inp.name)) {
                Some(
                    CallInput(inp.name, ExprIdentifier(inp.name)(SourceLocation.empty))(
                        SourceLocation.empty
                    )
                )
              } else if (inp.wdlType.isInstanceOf[TypeOptional]) {
                None
              } else {
                Some(
                    CallInput(inp.name, ValueString("set my value!")(SourceLocation.empty))(
                        SourceLocation.empty
                    )
                )
              }
            }
            Some(CallInputs(task.input.get.parameters.flatMap(getInputValue))(SourceLocation.empty))
          } else {
            None
          }
          Call(task.name, None, Vector.empty, callInputs)(SourceLocation.empty)
      }

      val (wfInputSection, _) = getInput(inputs)
      Workflow(
          name.get,
          wfInputSection,
          getOutput(outputs),
          getMeta(
              Map("title" -> title, "summary" -> summary, description -> "description").collect {
                case (key: String, Some(value: String)) => key -> value
              }
          ),
          getParameterMeta(inputs),
          calls
      )(SourceLocation.empty)
    }
  }
}
