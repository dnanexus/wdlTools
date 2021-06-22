package wdlTools.types

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import dx.util.{FileNode, FileSourceResolver, JsUtils, Logger}
import wdlTools.generators.code.Fixer
import wdlTools.syntax.Parsers

import java.net.URI
import java.nio.file.{Files, Path, Paths}

// If the corpora of WDL files has been fetched to src/test/resources/corpora,
// these tests will parse and type-check the WDL files and will fail if there
// are any errors.
class CorporaTest extends AnyWordSpec with Matchers {
  private val corporaDir = Paths.get(getClass.getResource(s"/corpora").getPath)
  private val configFile = Paths.get(getClass.getResource(s"/corpora_repos.json").getPath)
  private val corporaAvailable = Files.exists(configFile) && Files.isDirectory(corporaDir)

  if (corporaAvailable) {
    val logger = Logger.Quiet
    lazy val fixDir = {
      val fixDir = Files.createTempDirectory("fix")
      fixDir.toFile.deleteOnExit()
      fixDir
    }
    lazy val uniqueDir = Iterator.from(0)

    def check(source: FileNode, fileResolver: FileSourceResolver, fix: Boolean): Unit = {
      if (fix) {
        // If fix is true, we need to fix the document first and write it to a tempdir.
        // The Fixer takes care of checking that the fixed file is valid.
        val fixer = Fixer(fileResolver = fileResolver)
        fixer.fix(source, fixDir.resolve(uniqueDir.next().toString))
      } else {
        val doc = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
          .parseDocument(source)
        val checker = TypeInfer(fileResolver = fileResolver, logger = logger)
        checker.apply(doc)
      }
    }

    def parseAndTypeCheck(dir: Path, file: String, fix: Boolean, expectFailure: Boolean): Unit = {
      val fileResolver = FileSourceResolver.create(Vector(dir))
      val sourceFile = fileResolver.fromPath(dir.resolve(file))
      if (expectFailure) {
        assertThrows[Exception] {
          check(sourceFile, fileResolver, fix)
        }
      } else {
        check(sourceFile, fileResolver, fix)
      }
    }

    val corpora = JsUtils.getValues(JsUtils.jsFromFile(configFile), Some("corpora"))

    "corpora test" should {
      corpora.map(JsUtils.getFields(_)).foreach { corpus =>
        val uri = URI.create(JsUtils.getString(corpus("url")))
        val repo = Paths.get(uri.getPath).getFileName.toString match {
          case s if s.endsWith(".git") => s.dropRight(4)
          case s                       => s
        }
        val root = corporaDir.resolve(repo)
        val fix = JsUtils.getOptionalBoolean(corpus, "fix").getOrElse(false)
        val expectFailure = JsUtils.getOptionalBoolean(corpus, "fail").getOrElse(false)
        JsUtils.getValues(corpus("entrypoints")).map(JsUtils.getFields(_)).foreach {
          case example if example.contains("path") =>
            val path = JsUtils.getString(example("path"))
            s"parse and type-check ${root}/${path}" in {
              parseAndTypeCheck(root, path, fix, expectFailure)
            }
          case example =>
            val dir = JsUtils.getOptionalString(example, "dir").map(root.resolve).getOrElse(root)
            val exclude = JsUtils
              .getOptionalValues(example, "exclude")
              .map(_.map(JsUtils.getString(_)).toSet)
              .getOrElse(Set.empty)
            Files
              .list(dir)
              .filter(!Files.isDirectory(_))
              .map(_.getFileName.toString)
              .forEach {
                case name if name.endsWith(".wdl") && !exclude.contains(name) =>
                  s"parse and type-check ${dir}/${name}" in {
                    parseAndTypeCheck(dir, name, fix, expectFailure)
                  }
              }
        }
      }
    }
  }
}
