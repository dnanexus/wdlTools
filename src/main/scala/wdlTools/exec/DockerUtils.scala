package wdlTools.exec

import wdlTools.util.{Logger, Util}

case class DockerUtils(logger: Logger) {
  def pullImage(name: String): Option[String] = {
    var retry_count = 5
    while (retry_count > 0) {
      try {
        val (outstr, errstr) = Util.execCommand(s"docker pull ${name}")
        logger.trace(
            s"""|output:
                |${outstr}
                |stderr:
                |${errstr}""".stripMargin
        )
        return Some(name)
      } catch {
        // ideally should catch specific exception.
        case _: Throwable =>
          retry_count = retry_count - 1
          logger.trace(
              s"""Failed to pull docker image:
                 |${name}. Retrying... ${5 - retry_count}
                    """.stripMargin
          )
          Thread.sleep(1000)
      }
    }
    throw new RuntimeException(s"Unable to pull docker image: ${name} after 5 tries")
  }

//  def dockerImage(env: Map[String, WdlValues.V]): Option[String] = {
//    val dImg = dockerImageEval(env)
//    dImg match {
//      case Some(url) if url.startsWith(DxPath.DX_URL_PREFIX) =>
//        // a tarball created with "docker save".
//        // 1. download it
//        // 2. open the tar archive
//        // 2. load into the local docker cache
//        // 3. figure out the image name
//        logger.trace(s"looking up dx:url ${url}")
//        val dxFile = dxApi.resolveDxUrlFile(url)
//        val fileName = dxFile.describe().name
//        val tarballDir = Paths.get(DOCKER_TARBALLS_DIR)
//        SysUtils.safeMkdir(tarballDir)
//        val localTar: Path = tarballDir.resolve(fileName)
//
//        logger.trace(s"downloading docker tarball to ${localTar}")
//        dxApi.downloadFile(localTar, dxFile)
//
//        logger.trace("figuring out the image name")
//        val (mContent, _) = Util.execCommand(s"tar --to-stdout -xf ${localTar} manifest.json")
//        logger.traceLimited(
//            s"""|manifest content:
//                |${mContent}
//                |""".stripMargin
//        )
//        val repo = TaskRunner.readManifestGetDockerImageName(mContent)
//        logger.trace(s"repository is ${repo}")
//
//        logger.trace(s"load tarball ${localTar} to docker", minLevel = TraceLevel.None)
//        val (outstr, errstr) = Util.execCommand(s"docker load --input ${localTar}")
//        logger.traceLimited(
//            s"""|output:
//                |${outstr}
//                |stderr:
//                |${errstr}""".stripMargin
//        )
//        Some(repo)
//
//      case Some(dImg) =>
//        pullImage(dImg)
//
//      case _ =>
//        dImg
//    }
//  }
}
