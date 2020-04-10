package wdlTools.syntax

import AbstractSyntax._
import java.nio.file.Paths

import org.scalatest.{FlatSpec, Matchers}
import wdlTools.syntax.v1_0.ParseAll
import wdlTools.util.{Options, SourceCode, Util, Verbosity}

class AbstractSyntaxTest extends FlatSpec with Matchers {
  private val tasksDir = Paths.get(getClass.getResource("/syntax/v1_0/tasks").getPath)
  private val workflowsDir = Paths.get(getClass.getResource("/syntax/v1_0/workflows").getPath)
  private val opts =
    Options(antlr4Trace = false,
            localDirectories = Some(Vector(tasksDir, workflowsDir)),
            verbosity = Verbosity.Quiet)
  private val loader = SourceCode.Loader(opts)
  private val parser = ParseAll(opts, loader)

  private def getTaskSource(fname: String): SourceCode = {
    loader.apply(Util.getURL(tasksDir.resolve(fname)))
  }

  private def getWorkflowSource(fname: String): SourceCode = {
    loader.apply(Util.getURL(workflowsDir.resolve(fname)))
  }

  it should "handle import statements" in {
    val doc = parser.apply(getWorkflowSource("imports.wdl"))

    doc.version shouldBe WdlVersion.V1_0

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe 2

    doc.workflow should not be empty
  }

  it should "handle optionals" in {
    val doc = parser.apply(getTaskSource("missing_type_bug.wdl"))
    doc.version shouldBe WdlVersion.V1_0
  }

  it should "type check GATK tasks" in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/tasks/JointGenotypingTasks-terra.wdl"
    val sourceCode = loader.apply(Util.getURL(url))
    val doc = parser.apply(sourceCode)

    doc.version shouldBe WdlVersion.V1_0
  }

  it should "type check GATK joint genotyping workflow" taggedAs Edge in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping-terra.wdl"
    val sourceCode = loader.apply(Util.getURL(url))
    val doc = parser.apply(sourceCode)

    doc.version shouldBe WdlVersion.V1_0
  }
}
