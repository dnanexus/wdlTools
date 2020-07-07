package wdlTools.exec

//import java.net.URI
//import java.nio.file.Path
//
//import spray.json.JsNull
//import wdlTools.eval.EvalConfig
//import wdlTools.types.{TypedAbstractSyntax => TAT}
//import wdlTools.util.{Logger, Options}

abstract class AbstractTaskExecutor { //(document: TAT.Document, opts: Options, evalCfg: EvalConfig) {
//  private lazy val dockerUtils: DockerUtils = DockerUtils(opts, evalCfg, docSourceUri)
//  private val logger: Logger = opts.logger
//  private val docSourceUri = document.sourceUri
//  private val task: TAT.Task = document.elements.collect {
//    case task: TAT.Task => task
//  } match {
//    case Vector(task: TAT.Task) => task
//    case _ =>
//      throw new ExecException("Can only execute WDL document with single task",
//                              document.text,
//                              docSourceUri)
//  }

//  /**
//    * Localize task inputs.
//    * @param taskInputs
//    * @return
//    */
//  def prolog(
//      taskInputs: Map[TAT.InputDefinition, WdlValues.V]
//  ): (Map[String, (WdlTypes.T, WdlValues.V)], Map[Furl, Path]) = {
//    val parameterMeta: Map[String, TAT.MetaValue] = task.parameterMeta match {
//      case None                                   => Map.empty
//      case Some(TAT.ParameterMetaSection(kvs, _)) => kvs
//    }
//
//    // Download/stream all input files.
//    //
//    // Note: this may be overly conservative,
//    // because some of the files may not actually be accessed.
//    val (localizedInputs, dxUrl2path, dxdaManifest, dxfuseManifest) =
//      jobInputOutput.localizeFiles(parameterMeta, taskInputs, dxPathConfig.inputFilesDir)
//
//    // build a manifest for dxda, if there are files to download
//    val DxdaManifest(manifestJs) = dxdaManifest
//    if (manifestJs.asJsObject.fields.nonEmpty) {
//      Util.writeFileContent(dxPathConfig.dxdaManifest, manifestJs.prettyPrint)
//    }
//
//    // build a manifest for dxfuse
//    val DxfuseManifest(manifest2Js) = dxfuseManifest
//    if (manifest2Js != JsNull) {
//      Util.writeFileContent(dxPathConfig.dxfuseManifest, manifest2Js.prettyPrint)
//    }
//
//    val inputsWithTypes: Map[String, (WdlTypes.T, WdlValues.V)] =
//      localizedInputs.map {
//        case (inpDfn, value) =>
//          inpDfn.name -> (inpDfn.wdlType, value)
//      }
//
//    logger.traceLimited(s"Epilog: complete, inputsWithTypes = ${localizedInputs}")
//    (inputsWithTypes, dxUrl2path)
//  }
}
