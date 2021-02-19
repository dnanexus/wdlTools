package wdlTools.syntax.v1_1

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.syntax.v1_1.ConcreteSyntax.Document

import java.nio.file.Paths

class ConcreteSyntaxV1_1Test extends AnyFlatSpec with Matchers {
  private val sourcePath = Paths.get(getClass.getResource("/syntax/v1_1").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(sourcePath))
  private val logger = Logger.Quiet

  private def getDocument(fname: String): Document = {
    ParseTop(
        WdlV1_1Grammar.newInstance(fileResolver.fromPath(sourcePath.resolve(fname)),
                                   Vector.empty,
                                   logger = logger)
    ).parseDocument
  }

  it should "parse sep option and sep function" in {
    getDocument("sep.wdl")
  }
}
