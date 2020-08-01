package wdlTools.exec

import java.nio.file.Files

import spray.json._
import wdlTools.eval.EvalException
import wdlTools.syntax.SourceLocation
import wdlTools.util.{
  FileSourceResolver,
  FileUtils,
  Logger,
  NoSuchProtocolException,
  SysUtils,
  TraceLevel
}

import scala.util.{Success, Try}

case class DockerUtils(fileResolver: FileSourceResolver = FileSourceResolver.get,
                       logger: Logger = Logger.get) {
  private lazy val DOCKER_TARBALLS_DIR = {
    val p = Files.createTempDirectory("docker-tarballs")
    sys.addShutdownHook({
      FileUtils.deleteRecursive(p)
    })
    p
  }

  // pull a Docker image from a repository - requires Docker client to be installed
  def pullImage(name: String, loc: SourceLocation, maxRetries: Int = 3): String = {
    def pull(retry: Int): Option[String] = {
      try {
        // stdout will be the full image name
        val (_, stdout, stderr) = SysUtils.execCommand(s"docker pull --quiet ${name}")
        logger.trace(
            s"""|output:
                |${stdout}
                |stderr:
                |${stderr}""".stripMargin
        )
        return Some(stdout.trim)
      } catch {
        // ideally should catch specific exception.
        case t: Throwable =>
          logger.trace(
              s"""Failed to pull docker image:
                 |${name}. Retrying... ${maxRetries - retry}
                    """.stripMargin,
              exception = Some(t)
          )
          Thread.sleep(1000)
      }
      None
    }

    (0 to maxRetries)
      .collectFirst { retry =>
        pull(retry) match {
          case Some(name) => name
        }
      }
      .getOrElse(
          throw new ExecException(s"Unable to pull docker image: ${name} after ${maxRetries} tries",
                                  loc)
      )
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
  private[exec] def readManifestGetDockerImageName(buf: String): Option[String] = {
    val jso = buf.parseJson
    val elem = jso match {
      case JsArray(elements) if elements.nonEmpty => elements.head
      case other =>
        logger.warning(s"bad value ${other} for manifest, expecting non empty array")
        return None
    }
    elem.asJsObject.fields.get("RepoTags") match {
      case None | Some(JsNull) =>
        logger.warning("The repository is not specified for the image")
        None
      case Some(JsString(repo)) =>
        Some(repo)
      case Some(JsArray(elements)) if (elements.isEmpty) =>
        logger.warning("RepoTags has an empty array")
        None
      case Some(JsArray(elements)) =>
        elements.head match {
          case JsString(repo) => Some(repo)
          case other =>
            logger.warning(s"bad value ${other} in RepoTags manifest field")
            None
        }
      case other =>
        logger.warning(s"bad value ${other} in RepoTags manifest field")
        None
    }
  }

  // If `nameOrUrl` is a URL, the Docker image tarball is downloaded using `IoSupp.downloadFile`
  // and loaded using `docker load`. Otherwise, it is assumed to be an image name and is pulled
  // with `pullImage`. Requires Docker client to be installed.
  def getImage(nameOrUrl: String, loc: SourceLocation): String = {
    if (nameOrUrl.contains("://")) {
      // a tarball created with "docker save".
      // 1. download it
      // 2. open the tar archive
      // 2. load into the local docker cache
      // 3. figure out the image name
      logger.traceLimited(s"downloading docker tarball to ${DOCKER_TARBALLS_DIR}")
      val localTarSrc =
        try {
          fileResolver.resolve(nameOrUrl)
        } catch {
          case e: NoSuchProtocolException =>
            throw new EvalException(e.getMessage, loc)
        }
      val localTar = localTarSrc.localizeToDir(DOCKER_TARBALLS_DIR, overwrite = true)
      logger.traceLimited("figuring out the image name")
      val (_, mContent, _) = SysUtils.execCommand(s"tar --to-stdout -xf ${localTar} manifest.json")
      logger.traceLimited(
          s"""|manifest content:
              |${mContent}
              |""".stripMargin
      )
      // TODO: I'm not sure that the manifest should take priority over the output of 'docker load'
      val repo = readManifestGetDockerImageName(mContent)
      logger.traceLimited(s"repository is ${repo}")
      logger.traceLimited(s"load tarball ${localTar} to docker", minLevel = TraceLevel.None)
      val (_, outstr, errstr) = SysUtils.execCommand(s"docker load --input ${localTar}")
      logger.traceLimited(
          s"""|output:
              |${outstr}
              |stderr:
              |${errstr}""".stripMargin
      )
      repo match {
        case None =>
          val dockerLoadRegexp = "^Loaded image: (.+)$".r
          outstr.trim match {
            case dockerLoadRegexp(r) => r
            case _ =>
              throw new Exception(
                  s"Could not determine the repo name from either the manifest or the 'docker load' output ${outstr}"
              )
          }
        case Some(r) => r
      }
    } else {
      pullImage(nameOrUrl, loc)
    }
  }

  def getImage(nameOrUrlVec: Vector[String], loc: SourceLocation): String = {
    nameOrUrlVec
      .collectFirst { nameOrUrl =>
        Try(getImage(nameOrUrl, loc)) match {
          case Success(value) => value
        }
      }
      .getOrElse(
          throw new ExecException(
              s"Could not get image from any of ${nameOrUrlVec}",
              loc
          )
      )
  }
}
