package wdlTools.generators.project

import spray.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue}
import wdlTools.syntax.AbstractSyntax._

object TestsGenerator {
  def apply(wdlName: String, doc: Document): String = {
    var data: Map[String, JsValue] = Map.empty

    def getExampleValue(value: Type): JsValue = {
      value match {
        case _: TypeString      => JsString("foo")
        case _: TypeFile        => JsObject(Map("url" -> JsString("http://url/of/data/file")))
        case _: TypeInt         => JsNumber(0)
        case _: TypeFloat       => JsNumber(1.0)
        case _: TypeBoolean     => JsBoolean(true)
        case TypeArray(t, _, _) => JsArray(getExampleValue(t))
        case TypeMap(k, v, _)   => JsObject(k.toString -> getExampleValue(v))
        case _: TypeObject      => JsObject("foo" -> JsString("bar"))
        case TypePair(l, r, _) =>
          JsObject("left" -> getExampleValue(l), "right" -> getExampleValue(r))
        case TypeStruct(_, members, _) =>
          JsObject(members.collect {
            case StructMember(name, dataType, _) if !dataType.isInstanceOf[TypeOptional] =>
              name -> getExampleValue(dataType)
          }.toMap)
        case other => throw new Exception(s"Unrecognized type ${other}")
      }
    }

    def addData(declarations: Vector[Declaration]): JsObject = {
      JsObject(declarations.collect {
        case Declaration(name, wdlType, expr, _)
            if expr.isEmpty && !wdlType.isInstanceOf[TypeOptional] && !data.contains(name) =>
          val dataName = s"input_${name}"
          data += (dataName -> getExampleValue(wdlType))
          name -> JsString(dataName)
      }.toMap)
    }

    def addInputs(inputs: Option[InputSection]): JsObject = {
      if (inputs.isDefined) {
        addData(inputs.get.parameters)
      } else {
        JsObject()
      }
    }

    def addOutputs(outputs: Option[OutputSection]): JsObject = {
      if (outputs.isDefined) {
        addData(outputs.get.parameters)
      } else {
        JsObject()
      }
    }

    def createTest(name: String,
                   wdl: String,
                   inputs: JsObject,
                   expected: JsObject,
                   taskName: Option[String] = None): JsObject = {
      val fields: Map[String, JsValue] = Map(
          "name" -> JsString(name),
          "wdl" -> JsString(wdl),
          "inputs" -> inputs,
          "expected" -> expected
      )
      val taskNameField = if (taskName.isDefined) {
        Map("task_name" -> JsString(taskName.get))
      } else {
        Map.empty
      }
      JsObject(fields ++ taskNameField)
    }

    val workflowTest: Vector[JsObject] = Vector(doc.workflow.map { workflow =>
      createTest(
          name = s"test_workflow_${workflow.name}",
          wdlName,
          addInputs(workflow.input),
          addOutputs(workflow.output)
      )
    }).flatten

    val taskTests: Vector[JsObject] = doc.elements.collect {
      case task: Task =>
        createTest(
            name = s"test_task_${task.name}",
            wdlName,
            addInputs(task.input),
            addOutputs(task.output),
            Some(task.name)
        )
    }

    JsObject(
        "data" -> JsObject(data),
        "tests" -> JsArray(workflowTest ++ taskTests)
    ).prettyPrint
  }
}
