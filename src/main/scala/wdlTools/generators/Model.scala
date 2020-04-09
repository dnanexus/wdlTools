package wdlTools.generators

import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.WdlVersion

import scala.collection.mutable

object Model {
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

  def getInput(inputs: Vector[Field]): Option[InputSection] = {
    if (inputs.isEmpty) {
      None
    } else {
      Some(InputSection(inputs.map(_.toDeclaration), null, None))
    }
  }

  def getOutput(outputs: Vector[Field]): Option[OutputSection] = {
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

  def getParameterMeta(inputs: Vector[Field]): Option[ParameterMetaSection] = {
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

  case class TaskSpec(var name: Option[String] = None,
                      var title: Option[String] = None,
                      var summary: Option[String] = None,
                      var description: Option[String] = None,
                      var docker: Option[String] = None,
                      inputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty,
                      outputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty) {
    def toTask: Task = {
      Task(
          name.get,
          getInput(inputs.toVector),
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
    }
  }

  case class WorkflowSpec(wdlVersion: WdlVersion,
                          var name: Option[String] = None,
                          var title: Option[String] = None,
                          var summary: Option[String] = None,
                          var description: Option[String] = None,
                          inputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty,
                          outputs: mutable.Buffer[Field] = mutable.ArrayBuffer.empty,
                          tasks: mutable.Buffer[TaskSpec] = mutable.ArrayBuffer.empty) {
    def toDocument: Document = {
      val wfInputs = getInput(inputs.toVector)
      lazy val wfInputMap: Map[String, Declaration] = if (wfInputs.isDefined) {
        wfInputs.get.declarations.map { inp =>
          inp.name -> inp
        }.toMap
      } else {
        Map.empty
      }

      val (body: Seq[Task], calls: Seq[Call]) = tasks.map { taskSpec =>
        val task = taskSpec.toTask
        val callInputs: Map[String, Expr] = if (task.input.isDefined) {
          def getInputValue(inp: Declaration): Option[(String, Expr)] = {
            if (wfInputMap.contains(inp.name)) {
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
        val call = Call(task.name, None, callInputs, null, None)
        (task, call)
      }.unzip

      val wf = Workflow(
          name.get,
          wfInputs,
          getOutput(outputs.toVector),
          getMeta(
              Map("title" -> title, "summary" -> summary, description -> "description").collect {
                case (key: String, Some(value: String)) => key -> value
              }
          ),
          getParameterMeta(inputs.toVector),
          calls.toVector,
          null,
          None
      )

      Document(wdlVersion, null, body.toVector, Some(wf), null, None)
    }
  }
}
