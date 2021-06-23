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
        val parsers = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
        val doc = parsers.parseDocument(source)
        val checker = TypeInfer(fileResolver = fileResolver, logger = logger)
        checker.apply(doc)
      }
    }

    def parseAndTypeCheck(dir: Path,
                          file: Path,
                          fix: Boolean,
                          expectFailure: Boolean,
                          importDirs: Vector[Path]): Unit = {
      val fileResolver = FileSourceResolver.create(dir +: importDirs)
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
        JsUtils.getValues(corpus("entrypoints")).map(JsUtils.getFields(_)).foreach { example =>
          val fix = JsUtils.getOptionalBoolean(example, "fix").getOrElse(false)
          val expectFailure = JsUtils.getOptionalBoolean(example, "fail").getOrElse(false)
          val importDirs = JsUtils
            .getOptionalValues(example, "import_dirs")
            .map(_.map(d => root.resolve(JsUtils.getString(d))))
            .getOrElse(Vector.empty)
          if (example.contains("path")) {
            val path = Paths.get(JsUtils.getString(example("path")))
            s"parse and type-check ${root.resolve(path)}" in {
              parseAndTypeCheck(root, path, fix, expectFailure, importDirs)
            }
          } else {
            val dir = JsUtils.getOptionalString(example, "dir").map(root.resolve).getOrElse(root)
            val include = JsUtils
              .getOptionalValues(example, "include")
              .map(_.map(i => Paths.get(JsUtils.getString(i))).toSet)
            val exclude = JsUtils
              .getOptionalValues(example, "exclude")
              .map(_.map(e => Paths.get(JsUtils.getString(e))).toSet)
            Files
              .list(dir)
              .filter(!Files.isDirectory(_))
              .filter { path =>
                path.getFileName.toString.endsWith(".wdl") &&
                include.forall(_.exists(i => path.endsWith(i))) &&
                !exclude.exists(_.exists(e => path.endsWith(e)))
              }
              .forEach { path =>
                s"parse and type-check ${dir.resolve(path)}" in {
                  parseAndTypeCheck(dir, path, fix, expectFailure, importDirs)
                }
              }
          }
        }
      }
    }
  }
}
