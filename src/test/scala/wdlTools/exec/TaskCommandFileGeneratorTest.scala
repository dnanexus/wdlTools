package wdlTools.exec

import dx.util.{FileUtils, Logger, PosixPath}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TaskCommandFileGeneratorTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Quiet
  private val containerMountDir = PosixPath("/home/wdlTools")

  private def renderDockerScript(shmSize: Option[String] = None,
                                 ipcMode: Option[String] = None): String = {
    val (hostPaths, guestPaths) =
      DefaultExecPaths.createLocalContainerPair(containerMountDir = containerMountDir)
    val generator = TaskCommandFileGenerator(logger)
    val scriptPath = generator.writeDockerRunScript(
        imageName = "test-image:latest",
        hostPaths = hostPaths,
        guestPaths = guestPaths,
        maxMemory = 1024L * 1024L * 1024L,
        shmSize = shmSize,
        ipcMode = ipcMode
    )
    FileUtils.readFileContent(scriptPath)
  }

  it should "omit --shm-size and --ipc when neither is set" in {
    val script = renderDockerScript()
    script should not include "--shm-size"
    script should not include "--ipc="
    script should include("--memory=1073741824")
    script should include("test-image:latest")
  }

  it should "include quoted --shm-size when shmSize is set" in {
    val script = renderDockerScript(shmSize = Some("8g"))
    script should include("\"--shm-size=8g\"")
    script should not include "--ipc="
  }

  it should "include quoted --ipc when ipcMode is set" in {
    val script = renderDockerScript(ipcMode = Some("host"))
    script should include("\"--ipc=host\"")
    script should not include "--shm-size"
  }

  it should "include both flags when both are set" in {
    val script = renderDockerScript(shmSize = Some("4g"), ipcMode = Some("host"))
    script should include("\"--shm-size=4g\"")
    script should include("\"--ipc=host\"")
  }

  it should "preserve --user/--hostname extraFlags when shm/ipc are set" in {
    val script = renderDockerScript(shmSize = Some("2g"), ipcMode = Some("host"))
    script should include("--user $(id -u):$(id -g)")
    script should include("--hostname $(hostname)")
  }

  it should "emit shm/ipc as direct docker run args, not via extraFlags shell var" in {
    // Defense-in-depth: callers (e.g. dxCompiler) are responsible for validating values, but
    // the rendered script also avoids the unquoted ${extraFlags} expansion path so that even
    // if an unvalidated value contained shell metas, it would appear as a single argv token.
    val script = renderDockerScript(shmSize = Some("2g"), ipcMode = Some("host"))
    script should not include "extraFlags=\"${extraFlags} --shm-size"
    script should not include "extraFlags=\"${extraFlags} --ipc"
  }

  it should "render shm-size on its own backslash-continued docker run line" in {
    // Pin the conditional render so a stray newline or missing trailing backslash in the
    // template would break this test rather than producing a broken bash command at runtime.
    val script = renderDockerScript(shmSize = Some("8g"), ipcMode = Some("host"))
    script should include("\"--shm-size=8g\" \\")
    script should include("\"--ipc=host\" \\")
    script should include("--entrypoint /bin/bash \\")
  }
}
