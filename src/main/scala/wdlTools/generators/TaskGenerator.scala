package wdlTools.generators

import wdlTools.generators.TaskGenerator.{Field, Model}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.util.InteractiveConsole

import scala.collection.mutable

case class TaskGenerator(model: Model,
                         interactive: Boolean = false,
                         renderer: Renderer = SspRenderer(),
                         template: String = "/templates/task/task.wdl.ssp",
                         defaultDockerImage: String = "debian:stretch-slim") {

  val typeChoices = Map(
      "String" -> TypeString,
      "Int" -> TypeInt,
      "Float" -> TypeFloat,
      "Boolean" -> TypeBoolean,
      "File" -> TypeFile,
      "Array[String]" -> TypeArray(TypeString),
      "Array[Int]" -> TypeArray(TypeInt),
      "Array[Float]" -> TypeArray(TypeFloat),
      "Array[Boolean]" -> TypeArray(TypeBoolean),
      "Array[File]" -> TypeArray(TypeFile)
  )

  def generate(): String = {
    if (interactive) {
      val console = InteractiveConsole(promptColor = Console.BLUE)

      if (model.name.isEmpty) {
        model.name = console.askOnce[String](prompt = "Task name")
      }
      if (model.title.isEmpty) {
        model.title = console.askOnce[String](prompt = "Task title", optional = true)
      }
      if (model.docker.isEmpty) {
        model.docker =
          console.askOnce[String](prompt = "Docker image ID", default = Some(defaultDockerImage))
      }

      def readFields(fieldType: String, fields: mutable.Buffer[Field]): Unit = {
        var continue: Boolean =
          console.askYesNo(prompt = s"Define ${fieldType}s interactively?", default = Some(true))
        while (continue) {
          val name = console.askRequired[String](prompt = "Name")
          val label = console.askOnce[String](prompt = "Label", optional = true)
          val help = console.askOnce[String](prompt = "Help", optional = true)
          val optional = console.askYesNo(prompt = "Optional", default = Some(true))
          val dataType = console
            .askRequired[String](prompt = "Type",
                                 choices = Some(typeChoices.keys.toVector),
                                 otherOk = true)
          val patterns = if (dataType.contains("File")) {
            console.ask[String](prompt = "Patterns", optional = true, multiple = true)
          } else {
            Vector.empty
          }
          def askDefaultAndChoices[T](
              isArray: Boolean
          )(implicit reader: InteractiveConsole.Reader[T]): (Option[Any], Seq[Any]) = {
            val default = if (isArray) {
              val seq = console.ask[T](prompt = "Default", optional = true, multiple = true)
              if (seq.isEmpty) {
                None
              } else {
                Some(seq)
              }
            } else {
              console.askOnce[T](prompt = "Default", optional = true)
            }
            val choices = console.ask[T](prompt = "Choice", optional = true, multiple = true)
            (default, choices)
          }
          def defaultAndChoices(dataType: Type, isArray: Boolean): (Option[Any], Seq[Any]) = {
            dataType match {
              case TypeString  => askDefaultAndChoices[String](isArray)
              case TypeInt     => askDefaultAndChoices[Int](isArray)
              case TypeFloat   => askDefaultAndChoices[Double](isArray)
              case TypeBoolean => askDefaultAndChoices[Boolean](isArray)
              case TypeFile    => askDefaultAndChoices[String](isArray)
              case _           => throw new Exception(s"Invalid type ${dataType}")
            }
          }
          val (default, choices) = if (typeChoices.contains(dataType)) {
            val astType = typeChoices(dataType)
            astType match {
              case TypeArray(nested, _) => defaultAndChoices(nested, isArray = true)
              case other                => defaultAndChoices(other, isArray = false)
            }
          } else {
            defaultAndChoices(TypeString, isArray = false)
          }
          fields.append(Field(name, label, help, optional, dataType, patterns, default, choices))
          continue = console.askYesNo(prompt = s"Define another ${fieldType}?")
        }
      }

      readFields(fieldType = "inputs", fields = model.inputs)

      readFields(fieldType = "output", fields = model.outputs)
    }

    renderer.render(template, Map("model" -> model))
  }
}

object TaskGenerator {

  case class Field(name: String,
                   label: Option[String] = None,
                   help: Option[String] = None,
                   optional: Boolean,
                   dataType: String,
                   patterns: Seq[String] = Vector.empty,
                   default: Option[Any] = None,
                   choices: Seq[Any] = Vector.empty)

  case class Model(version: String,
                   var name: Option[String] = None,
                   var title: Option[String] = None,
                   var docker: Option[String] = None,
                   inputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty,
                   outputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty)
}
