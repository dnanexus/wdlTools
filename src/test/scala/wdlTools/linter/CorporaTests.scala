package wdlTools.linter

import java.net.URL
import java.nio.file.Paths

import wdlTools.util.JsonUtil._
import org.scalatest.{Ignore, Tag}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import wdlTools.types.TypeOptions

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

  private def getLintCounts(events: Map[URL, Vector[LintEvent]]): Map[String, Int] = {
    events.values.flatten.toVector.map(_.rule.id).groupBy(x => x).map(x => x._2.head -> x._2.size)
  }

  "corpora test" should {
    "test linting of external WDL corpora" taggedAs CorporaAvailable in {
      val corpora =
        getValues(readJsonSource(Source.fromFile(configFile), configFile.toString)("corpora"))
      val opts = TypeOptions()
      val rules: Map[String, RuleConf] =
        (
            ParserRules.allRules.keys.toSet ++
              AbstractSyntaxTreeRules.allRules.keys.toSet ++
              TypedAbstractSyntaxTreeRules.allRules.keys.toSet
        ).map(id => id -> RuleConf(id, "x", "x", Severity.Error, Map.empty)).toMap

      corpora.map(getFields).foreach { corpus =>
        val root = corporaDirPath.resolve(if (corpus.contains("root")) {
          getString(corpus("root"))
        } else {
          val path = new URL(getString(corpus("url"))).getPath
          if (path.endsWith(".git")) {
            path.dropRight(4)
          } else {
            path
          }
        })
        getValues(corpus("entrypoints")).map(getFields).filter(_.contains("lint")).foreach {
          example =>
            val path = root.resolve(getString(example("path")))
            val url = path.toUri.toURL

            s"test lint in ${path}" in {
              path.toFile should exist

              val linter = Linter(opts, rules)
              val events = linter.apply(url)
              val actualLint = getLintCounts(events)
              val expectedLint = getFields(example("lint")).view.mapValues(getInt).toMap
              actualLint should equal(expectedLint)
            }
        }
      }
    }
  }
}
