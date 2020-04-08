package wdlTools.generators

import wdlTools.syntax.AbstractSyntax._
import wdlTools.syntax.{Parsers, WdlExprParser, WdlTypeParser, WdlVersion}
import wdlTools.util.{InteractiveConsole, Options}

import scala.collection.mutable

case class GeneratorConsole(opts: Options, wdlVersion: WdlVersion)
    extends InteractiveConsole(promptColor = Console.BLUE) {
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

  def readFields(fieldType: String, fields: mutable.Buffer[Model.Field]): Unit = {
    var continue: Boolean =
      askYesNo(prompt = s"Define ${fieldType}s interactively?", default = Some(true))
    while (continue) {
      val name = askRequired[String](prompt = "Name")
      val label = askOnce[String](prompt = "Label", optional = true)
      val help = askOnce[String](prompt = "Help", optional = true)
      val optional = askYesNo(prompt = "Optional", default = Some(true))
      val dataType: Type = typeParser.apply(
          askRequired[String](prompt = "Type", choices = Some(basicTypeChoices), otherOk = true)
      )
      val patterns = if (containsFile(dataType)) {
        ask[String](promptPrefix = "Patterns", optional = true, multiple = true)
      } else {
        Vector.empty
      }
      def askDefault: Option[Expr] = {
        askOnce[String](prompt = "Default", optional = true).map(exprParser.apply)
      }
      var default: Option[Expr] = askDefault
      while (default.isDefined && requiresEvaluation(default.get)) {
        error("Default value cannot be an expression that requires evaluation")
        default = askDefault
      }
      def askChoices: Seq[Expr] = {
        ask[String](promptPrefix = "Choice", optional = true, multiple = true).map(exprParser.apply)
      }
      var choices = askChoices
      while (choices.nonEmpty && choices.exists(requiresEvaluation)) {
        error("Choice value cannot be an expression that requires evaluation")
        choices = askChoices
      }
      fields.append(
          Model.Field(name, label, help, optional, dataType, patterns, default, choices)
      )
      continue = askYesNo(prompt = s"Define another ${fieldType}?")
    }
  }
}
