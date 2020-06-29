package wdlTools.exec

import java.net.URL
import java.nio.file.Files

import spray.json._
import wdlTools.eval.IoSupp
import wdlTools.syntax.TextSource
import wdlTools.util.{TraceLevel, Util}

case class DockerUtils(ioSupp: IoSupp, docSourceUrl: Option[URL]) {
  private val logger = ioSupp.opts.logger
  private lazy val DOCKER_TARBALLS_DIR = {
    val p = Files.createTempDirectory("docker-tarballs")
    sys.addShutdownHook({
      Util.deleteRecursive(p)
    })
    p
  }

  // pull a Docker image from a repository - requires Docker client to be installed
  def pullImage(name: String, text: TextSource): String = {
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
        return name
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
    throw new ExecException(s"Unable to pull docker image: ${name} after 5 tries",
                            text,
                            docSourceUrl)
  }

  // Read the manifest file from a docker tarball, and get the repository name.
  //
  // A manifest could look like this:
  // [
  //    {"Config":"4b778ee055da936b387080ba034c05a8fad46d8e50ee24f27dcd0d5166c56819.json",
  //     "RepoTags":["ubuntu_18_04_minimal:latest"],
  //     "Layers":[
  //          "1053541ae4c67d0daa87babb7fe26bf2f5a3b29d03f4af94e9c3cb96128116f5/layer.tar",
  //          "fb1542f1963e61a22f9416077bf5f999753cbf363234bf8c9c5c1992d9a0b97d/layer.tar",
  //          "2652f5844803bcf8615bec64abd20959c023d34644104245b905bb9b08667c8d/layer.tar",
  //          ]}
  // ]
  private[exec] def readManifestGetDockerImageName(buf: String): String = {
    val jso = buf.parseJson
    val elem = jso match {
      case JsArray(elements) if elements.nonEmpty => elements.head
      case other =>
        throw new Exception(s"bad value ${other} for manifest, expecting non empty array")
    }
    val repo: String = elem.asJsObject.fields.get("RepoTags") match {
      case None =>
        throw new Exception("The repository is not specified for the image")
      case Some(JsString(repo)) =>
        repo
      case Some(JsArray(elements)) =>
        if (elements.isEmpty)
          throw new Exception("RepoTags has an empty array")
        elements.head match {
          case JsString(repo) => repo
          case other          => throw new Exception(s"bad value ${other} in RepoTags manifest field")
        }
      case other =>
        throw new Exception(s"bad value ${other} in RepoTags manifest field")
    }
    repo
  }

  // If `nameOrUrl` is a URL, the Docker image tarball is downloaded using `IoSupp.downloadFile`
  // and loaded using `docker load`. Otherwise, it is assumed to be an image name and is pulled
  // with `pullImage`. Requires Docker client to be installed.
  def getImage(nameOrUrl: String, text: TextSource): String = {
    if (nameOrUrl.contains("://")) {
      // a tarball created with "docker save".
      // 1. download it
      // 2. open the tar archive
      // 2. load into the local docker cache
      // 3. figure out the image name
      logger.traceLimited(s"downloading docker tarball to ${DOCKER_TARBALLS_DIR}")
      val localTar = ioSupp.downloadFile(nameOrUrl, DOCKER_TARBALLS_DIR, overwrite = true, text)
      logger.traceLimited("figuring out the image name")
      val (mContent, _) = Util.execCommand(s"tar --to-stdout -xf ${localTar} manifest.json")
      logger.traceLimited(
          s"""|manifest content:
              |${mContent}
              |""".stripMargin
      )
      val repo = readManifestGetDockerImageName(mContent)
      logger.traceLimited(s"repository is ${repo}")
      logger.traceLimited(s"load tarball ${localTar} to docker", minLevel = TraceLevel.None)
      val (outstr, errstr) = Util.execCommand(s"docker load --input ${localTar}")
      logger.traceLimited(
          s"""|output:
              |${outstr}
              |stderr:
              |${errstr}""".stripMargin
      )
      repo
    } else {
      pullImage(nameOrUrl, text)
      nameOrUrl
    }
  }
}
