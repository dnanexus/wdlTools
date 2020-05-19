package wdlTools.linter

import java.nio.file.Paths

import wdlTools.util.JsonUtil._
import org.scalatest.{Ignore, Tag}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import wdlTools.util.Options

import scala.io.Source

// If the corpora of WDL files has been fetched to src/test/resources/corpora,
// these tests will run the linter against them and check for expected lint.
class CorporaTests extends AnyWordSpec with Matchers {
  private val corporaDirPath = Paths.get(getClass.getResource(s"/corpora").getPath)
  private val corporaDir = corporaDirPath.toFile
  private val configFile = Paths.get(getClass.getResource(s"/corpora_repos.json").getPath).toFile
  private object CorporaAvailable
      extends Tag(
          if (configFile.exists && corporaDir.exists && corporaDir.isDirectory) ""
          else classOf[Ignore].getName
      )

  private def getLintCounts(events: Vector[LintEvent]): Map[String, Int] = {
    events.map(_.rule.id).groupBy(x => x).map(x => x._2.head -> x._2.size)
  }

  "corpora test" should {
    "test linting of external WDL corpora" taggedAs CorporaAvailable in {
      val corpora =
        getValues(readJsonSource(Source.fromFile(configFile), configFile.toString)("corpora"))
      val opts = Options(followImports = true)
      val rules: Map[String, RuleConf] =
        (
            ParserRules.allRules.keys.toSet ++
              AbstractSyntaxTreeRules.allRules.keys.toSet ++
              TypedAbstractSyntaxTreeRules.allRules.keys.toSet
        ).map(id => id -> RuleConf(id, "x", "x", Severity.Error, Map.empty)).toMap

      corpora.map(getFields).foreach { corpus =>
        val root = if (corpus.contains("root")) {
          corporaDirPath.resolve(getString(corpus("root")))
        } else {
          corporaDirPath
        }
        getValues(corpus("entrypoints")).map(getFields).filter(_.contains("lint")).foreach {
          example =>
            val path = root.resolve(getString(example("path")))
            val url = path.toUri.toURL

            s"test lint in ${path}" in {
              path.toFile should exist

              val linter = Linter(opts, rules)
              linter.apply(url)

              val actualLint = getLintCounts(linter.events(url).toVector)
              val expectedLint = getFields(example("lint")).view.mapValues(getInt).toMap
              actualLint should equal(expectedLint)
            }
        }
      }
    }
  }
}
