package wdlTools.exec

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.util.{FileSourceResolver, Logger}

class ExecTest extends AnyFlatSpec with Matchers with Inside {
  private lazy val paths: ExecPaths = ExecPaths.createLocalPathsFromTemp()
  private lazy val fileResolver = FileSourceResolver.create(Vector(paths.getHomeDir()))
  private val dockerUtils = DockerUtils(fileResolver, Logger.Quiet)

  it should "read a docker manifest file" in {
    val buf = """|[
                 |{"Config":"4b778ee055da936b387080ba034c05a8fad46d8e50ee24f27dcd0d5166c56819.json",
                 |"RepoTags":["ubuntu_18_04_minimal:latest"],
                 |"Layers":[
                 |  "1053541ae4c67d0daa87babb7fe26bf2f5a3b29d03f4af94e9c3cb96128116f5/layer.tar",
                 |  "fb1542f1963e61a22f9416077bf5f999753cbf363234bf8c9c5c1992d9a0b97d/layer.tar",
                 |  "2652f5844803bcf8615bec64abd20959c023d34644104245b905bb9b08667c8d/layer.tar",
                 |  "386aac21291d1f58297bc7951ce00b4ff7485414d6a8e146d9fedb73e0ebfa5b/layer.tar",
                 |  "10d19fb34e1db6a5abf4a3c138dc21f67ef94c272cf359349da18ffa973b7246/layer.tar",
                 |  "c791705472caccd6c011326648cc9748bd1465451cd1cd28a809b0a7f4e8b671/layer.tar",
                 |  "d6cc894526fdfac9112633719d63806117b44cc7302f2a7ed6599b1a32f7c43a/layer.tar",
                 |  "3fbb031ee57d2a8b4b6615e540f55f9af88e88cdbceeffdac7033ec5c8ee327d/layer.tar"
                 |  ]
                 |}
                 |]""".stripMargin.trim

    val repo = dockerUtils.readManifestGetDockerImageName(buf)
    repo should equal(Some("ubuntu_18_04_minimal:latest"))
  }
}
