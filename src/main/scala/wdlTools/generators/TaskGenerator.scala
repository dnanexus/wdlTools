package wdlTools.generators

import java.net.URL
import java.nio.file.{Files, Path, Paths}

import wdlTools.formatter.V1_0Formatter
import wdlTools.generators.TaskGenerator.{Field, Model}
import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Parsers, WdlExprParser, WdlTypeParser, WdlVersion}
import wdlTools.util.{InteractiveConsole, Options, Util}

import scala.collection.mutable

case class TaskGenerator(opts: Options,
                         wdlVersion: WdlVersion = WdlVersion.V1_0,
                         interactive: Boolean = false,
                         readmes: Boolean = false,
                         overwrite: Boolean = false,
                         renderer: Renderer = SspRenderer(),
                         template: String = "/templates/task/task.wdl.ssp",
                         defaultDockerImage: String = "debian:stretch-slim",
                         generatedFiles: mutable.Map[URL, String] = mutable.HashMap.empty) {

  lazy val formatter: V1_0Formatter = V1_0Formatter(opts)
  lazy val parsers: Parsers = Parsers(opts)
  lazy val typeParser: WdlTypeParser = parsers.getTypeParser(wdlVersion)
  lazy val exprParser: WdlExprParser = parsers.getExprParser(wdlVersion)
  lazy val readmeGenerator: ReadmeGenerator =
    ReadmeGenerator(developerReadmes = true, readmes = generatedFiles)
  lazy val basicTypeChoices = Vector(
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

  def generate(model: Model, outputDir: Option[Path]): Unit = {
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
            console
              .askOnce[String](prompt = "Default", optional = true)
              .map(exprParser.apply)
          }
          var default: Option[Expr] = askDefault
          while (default.isDefined && requiresEvaluation(default.get)) {
            console.error("Default value cannot be an expression that requires evaluation")
            default = askDefault
          }
          def askChoices: Seq[Expr] = {
            console
              .ask[String](promptPrefix = "Choice", optional = true, multiple = true)
              .map(exprParser.apply)
          }
          var choices = askChoices
          while (choices.nonEmpty && choices.exists(requiresEvaluation)) {
            console.error("Choice value cannot be an expression that requires evaluation")
            choices = askChoices
          }
          fields.append(Field(name, label, help, optional, dataType, patterns, default, choices))
          continue = console.askYesNo(prompt = s"Define another ${fieldType}?")
        }
      }

      readFields(fieldType = "inputs", fields = model.inputs)

      readFields(fieldType = "output", fields = model.outputs)
    }

    val fname = s"${model.name}.wdl"
    val outputPath = if (outputDir.isDefined) {
      outputDir.get.resolve(fname)
    } else {
      Paths.get(fname)
    }
    val url = Util.getURL(outputPath)
    if (!overwrite && (generatedFiles.contains(url) || Files.exists(outputPath))) {
      throw new Exception(
          s"File ${outputPath} already exists; use --overwrite if you want to overwrite it"
      )
    }

    val doc = Document(wdlVersion, null, Vector(model.toTask), None, null, None)

    generatedFiles(url) = formatter.formatDocument(doc).mkString(System.lineSeparator())

    if (readmes) {
      readmeGenerator.apply(url, doc)
    }
  }
}

object TaskGenerator {

  case class Field(name: String,
                   label: Option[String] = None,
                   help: Option[String] = None,
                   optional: Boolean,
                   dataType: Type,
                   patterns: Seq[String] = Vector.empty,
                   default: Option[Expr] = None,
                   choices: Seq[Expr] = Vector.empty) {
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

  case class Model(version: WdlVersion,
                   var name: Option[String] = None,
                   var title: Option[String] = None,
                   var docker: Option[String] = None,
                   inputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty,
                   outputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty) {
    def toTask: Task = {
      val input = if (inputs.isEmpty) {
        None
      } else {
        Some(InputSection(inputs.map(_.toDeclaration).toVector, null, None))
      }
      val output = if (outputs.isEmpty) {
        None
      } else {
        Some(OutputSection(outputs.map(_.toDeclaration).toVector, null, None))
      }
      val meta = if (title.isEmpty) {
        None
      } else {
        Some(
            MetaSection(Vector(MetaKV("title", ValueString(title.get, null), null, None)),
                        null,
                        None)
        )
      }
      val parameterMeta = if (inputs.isEmpty) {
        None
      } else {
        val inputMetaKVs = inputs.flatMap(_.toMeta).toVector
        if (inputMetaKVs.isEmpty) {
          None
        } else {
          Some(ParameterMetaSection(inputMetaKVs, null, None))
        }
      }
      Task(
          name.get,
          input,
          output,
          CommandSection(Vector.empty, null, None),
          Vector.empty,
          meta,
          parameterMeta,
          Some(
              RuntimeSection(Vector(RuntimeKV("docker", ValueString(docker.get, null), null, None)),
                             null,
                             None)
          ),
          null,
          None
      )
    }
  }
}
