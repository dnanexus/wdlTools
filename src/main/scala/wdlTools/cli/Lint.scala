package wdlTools.cli

import java.io.{FileOutputStream, PrintStream}
import java.net.URL
import java.nio.file.{Files, Path}

import spray.json._
import wdlTools.linter.Severity.Severity
import wdlTools.linter.{LintEvent, Linter, RuleConf, Severity}
import wdlTools.util.JsonUtil.{getFields, getString, readJsonSource}

import scala.io.{AnsiColor, Source}
import scala.language.reflectiveCalls

case class Lint(conf: WdlToolsConf) extends Command {
  private val RULES_RESOURCE = "rules/lint_rules.json"
  private val rulePrototypes = {
    readJsonSource(Source.fromResource(RULES_RESOURCE), RULES_RESOURCE).map {
      case (ruleId, value) =>
        val fields = getFields(value)
        ruleId -> RuleConf(
            ruleId,
            getString(fields("name")),
            getString(fields("description")),
            fields
              .get("severity")
              .map(s => Severity.withName(getString(s)))
              .getOrElse(Severity.Default),
            fields
              .get("options")
              .map {
                case JsArray(names)   => names.map(getString(_) -> JsNull).toMap
                case JsObject(fields) => fields
                case _                => throw new Exception("Invalid lint_rules.json file format")
              }
              .getOrElse(Map.empty)
        )
    }
  }

  private def getRulePrototype(ruleId: String): RuleConf = {
    rulePrototypes.get(ruleId) match {
      case None       => throw new Exception(s"Invalid lint rule ID ${ruleId}")
      case Some(rule) => rule
    }
  }

  private def prototypeToRule(ruleId: String,
                              severity: Option[Severity] = None,
                              options: Option[Map[String, JsValue]] = None): RuleConf = {
    val prototype = getRulePrototype(ruleId)
    prototype.copy(severity = severity.getOrElse(prototype.severity),
                   options = options.getOrElse(prototype.options.filter(_._2 != JsNull)))
  }

  private lazy val getDefaultRules: Map[String, RuleConf] = {
    rulePrototypes.keys.map(ruleId => ruleId -> prototypeToRule(ruleId)).toMap
  }

  private def configFromFile(path: Path): Map[String, RuleConf] = {
    val config = readJsonSource(Source.fromFile(path.toFile), path.toString)

    def getRuleSpecs(key: String): Option[Vector[JsValue]] = {
      config.get(key) match {
        case None                  => None
        case Some(JsArray(values)) => Some(values)
        case _                     => throw new Exception(s"Invalid lint config file ${path}")
      }
    }

    val include = getRuleSpecs("include").map { ruleSpecs =>
      ruleSpecs.map {
        case JsString(ruleId) => ruleId -> prototypeToRule(ruleId)
        case JsObject(fields) =>
          val ruleId = getString(fields("ruleId"))
          ruleId -> prototypeToRule(
              ruleId,
              fields.get("severity").map(s => Severity.withName(getString(s))),
              fields.get("options").map(o => getFields(o))
          )
        case _ => throw new Exception(s"Invalid lint config file ${path}")
      }.toMap
    }

    val exclude = getRuleSpecs("exclude").map { ruleSpecs =>
      ruleSpecs.map {
        case JsString(value) => value
        case _               => throw new Exception(s"Invalid lint config file ${path}")
      }.toSet
    }

    include.getOrElse(getDefaultRules).removedAll(exclude.getOrElse(Set.empty))
  }

  private def configFromOptions(options: Seq[String]): Map[String, RuleConf] = {
    options.map { opt =>
      val parts = opt.split(":", 1)
      val ruleId = parts.head
      if (!rulePrototypes.contains(ruleId)) {
        throw new Exception(s"Invalid lint rule ID ${ruleId}")
      }
      val prototype = rulePrototypes(ruleId)
      val rule = if (parts.size == 1) {
        prototype
      } else {
        val optParts = parts(1).split(";")
        val severity = Severity.withName(optParts.head)
        val options: Map[String, JsValue] = if (optParts.size == 1) {
          prototype.options.filter(_._2 != JsNull)
        } else {
          optParts.tail
            .map(_.split("=") match {
              case Array(key: String, value: String) =>
                if (!prototype.options.contains(key)) {
                  throw new Exception(s"Invalid option ${key} for rule ${ruleId}")
                }
                key -> JsString(value)
            })
            .toMap
        }
        prototype.copy(severity = severity, options = options)
      }
      ruleId -> rule
    }.toMap
  }

  private def eventsToJson(events: Map[URL, Vector[LintEvent]],
                           rules: Map[String, RuleConf]): JsObject = {
    def eventToJson(err: LintEvent): JsObject = {
      val rule = rules(err.rule.id)
      JsObject(
          Map(
              "ruleId" -> JsString(rule.id),
              "ruleName" -> JsString(rule.name),
              "ruleDescription" -> JsString(rule.description),
              "severity" -> JsString(err.rule.severity.toString),
              "startLine" -> JsNumber(err.textSource.line),
              "startCol" -> JsNumber(err.textSource.col),
              "endLine" -> JsNumber(err.textSource.endLine),
              "endCol" -> JsNumber(err.textSource.endCol)
          )
      )
    }

    JsObject(Map("sources" -> JsArray(events.map {
      case (url, events) =>
        JsObject(
            Map("source" -> JsString(url.toString), "events" -> JsArray(events.map(eventToJson)))
        )
    }.toVector)))
  }

  private def severityToColor(severity: Severity): String = {
    severity match {
      case Severity.Error   => AnsiColor.RED
      case Severity.Warning => AnsiColor.YELLOW
      case Severity.Ignore  => AnsiColor.REVERSED
    }
  }

  private def printEvents(events: Map[URL, Vector[LintEvent]],
                          rules: Map[String, RuleConf],
                          printer: PrintStream,
                          effects: Boolean): Unit = {
    def colorMsg(msg: String, color: String): String = {
      if (effects) {
        s"${color}${msg}${AnsiColor.RESET}"
      } else {
        msg
      }
    }
    events.foreach { item =>
      val (msg, events) = item match {
        case (url, events) => (s"Lint in ${url}", events)
      }
      val border1 = "=" * msg.length
      val border2 = "-" * msg.length
      printer.println(border1)
      printer.println(colorMsg(msg, AnsiColor.BLUE))
      printer.println(border1)
      printer.println(colorMsg("Line:Col | Rule | Description", AnsiColor.BOLD))
      printer.println(border2)
      events.sortWith(_.textSource < _.textSource).foreach { event =>
        val rule = rules(event.rule.id)
        val eventMsg = if (event.message.isDefined) {
          s"${rule.description}: ${event.message.get}"
        } else {
          rule.description
        }
        val ruleDesc = if (effects) {
          colorMsg(eventMsg, severityToColor(event.rule.severity))
        } else {
          s"${event.rule.severity.toString}: ${eventMsg}"
        }
        printer.println(f"${event.textSource}%-9s| ${event.rule.id} | ${ruleDesc}")
      }
    }
  }

  override def apply(): Unit = {
    val url = conf.lint.url()
    val opts = conf.lint.getOptions
    val rulesConfig =
      if (conf.lint.config.isDefined) {
        configFromFile(conf.lint.config())
      } else if (conf.lint.includeRules.isDefined || conf.lint.excludeRules.isDefined) {
        val incl = conf.lint.includeRules.map(configFromOptions).getOrElse(getDefaultRules)
        val excl = conf.lint.excludeRules.map(_.toSet)
        incl.removedAll(excl.getOrElse(Set.empty))
      } else {
        getDefaultRules
      }
    val linter = Linter(opts, rulesConfig)
    linter.apply(url)

    if (linter.hasAnyEvents) {
      // Load rule descriptions
      val outputFile = conf.lint.outputFile.toOption
      val toFile = outputFile.isDefined
      val printer: PrintStream = if (toFile) {
        val resolved = outputFile.get
        if (!conf.lint.overwrite() && Files.exists(resolved)) {
          throw new Exception(s"File already exists: ${resolved}")
        }
        val fos = new FileOutputStream(outputFile.get.toFile)
        new PrintStream(fos, true)
      } else {
        System.out
      }
      if (conf.lint.json()) {
        val js = eventsToJson(linter.getOrderedEvents, rulePrototypes).prettyPrint
        printer.println(js)
      } else {
        printEvents(linter.getOrderedEvents, rulePrototypes, printer, effects = !toFile)
      }
      if (toFile) {
        printer.close()
      }
    }
  }
}
