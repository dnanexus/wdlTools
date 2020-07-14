package wdlTools.cli

import wdlTools.eval.{Eval, Serialize, WdlValues}
import wdlTools.exec.{
  DefaultTaskExecutor,
  ExecPaths,
  TaskContext,
  TaskExecutorCommandFailure,
  TaskExecutorInternalError,
  TaskExecutorSuccess
}
import wdlTools.syntax.Parsers
import wdlTools.types.{TypeInfer, TypedAbstractSyntax => TAT}
import wdlTools.util.{FileUtils, JsUtils, Util}

import scala.language.reflectiveCalls

case class Exec(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val opts = conf.exec.getOptions
    val logger = opts.logger
    val docSource = opts.fileResolver.resolve(conf.exec.uri())
    val parsers = Parsers(opts)
    val checker = TypeInfer(opts)
    val (doc: TAT.Document, _) = checker.apply(parsers.parseDocument(docSource))
    val taskName = conf.exec.task.toOption
    val tasks = doc.elements.collect {
      case task: TAT.Task if taskName.isEmpty          => task
      case task: TAT.Task if taskName.get == task.name => task
    }
    if (tasks.isEmpty) {}
    if (tasks.size > 1) {
      throw new Exception(
          s"""WDL file ${docSource} has ${tasks.size} tasks - use '--task' 
             |option to specify which one to execute""".stripMargin
      )
    }
    val task = tasks.head
    val inputsString = conf.exec.inputsFile.toOption match {
      case None       => FileUtils.readStdinContent()
      case Some(path) => FileUtils.readFileContent(path)
    }
    val runtimeDefaults: Map[String, WdlValues.V] = conf.exec.runtimeDefaults.toOption match {
      case None => Map.empty
      case Some(path) =>
        JsUtils.getFields(JsUtils.jsFromFile(path)).view.mapValues(Serialize.fromJson).toMap
    }
    val (execPaths, tempDir) = ExecPaths.createFromTemp(logger = logger)
    val evaluator = Eval(opts, execPaths, doc.version.value)
    val taskContext = TaskContext.fromJson(
        JsUtils.getFields(JsUtils.jsFromString(inputsString)),
        task,
        runtimeDefaults,
        evaluator,
        logger = logger
    )
    val useDocker = conf.exec.container()
    val taskExecutor = DefaultTaskExecutor(taskContext, execPaths, logger, useDocker)
    logger.traceLimited(s"""Executing task ${task.name} in ${tempDir}""")
    val result = taskExecutor.run
    if (conf.exec.summaryFile.isDefined) {
      JsUtils.jsToFile(taskExecutor.summary, conf.exec.summaryFile())
    }
    result match {
      case TaskExecutorSuccess(_, outputs, _, _) =>
        conf.exec.outputsFile.toOption match {
          case None =>
            System.out.println(JsUtils.jsToString(outputs))
          case Some(path) =>
            JsUtils.jsToFile(outputs, path)
        }
      case TaskExecutorCommandFailure(returnCode, _, stderr) =>
        throw new Exception(
            s"""Task ${task.name} command failed with return code ${returnCode}; stderr:
               |${stderr}""".stripMargin
        )
      case TaskExecutorInternalError(message, error) =>
        throw new Exception(Util.errorMessage(message, error))
    }
  }
}
