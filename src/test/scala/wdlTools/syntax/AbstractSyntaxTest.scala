package wdlTools.syntax

import AbstractSyntax._
import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.syntax.v1.ParseAll
import dx.util.{FileNode, FileSourceResolver, Logger, StringFileNode}

class AbstractSyntaxTest extends AnyFlatSpec with Matchers {
  private val tasksDir = Paths.get(getClass.getResource("/syntax/v1/tasks").getPath)
  private val workflowsDir =
    Paths.get(getClass.getResource("/syntax/v1/workflows").getPath)
  private val fileResolver = FileSourceResolver.create(Vector(tasksDir, workflowsDir))
  private val logger = Logger.Quiet
  private val parser = ParseAll(fileResolver = fileResolver, logger = logger)

  private def getTaskSource(fname: String): FileNode = {
    fileResolver.fromPath(tasksDir.resolve(fname))
  }

  private def getWorkflowSource(fname: String): FileNode = {
    fileResolver.fromPath(workflowsDir.resolve(fname))
  }

  it should "handle import statements" in {
    val doc = parser.parseDocument(getWorkflowSource("imports.wdl"))

    doc.version.value shouldBe WdlVersion.V1

    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe 2

    doc.workflow should not be empty
  }

  it should "handle file with native app tasks" in {
    val doc = ParseAll(followImports = true, fileResolver, logger = logger)
      .parseDocument(getTaskSource("call_native_app.wdl"))
    val imports = doc.elements.collect {
      case x: ImportDoc => x
    }
    imports.size shouldBe 1
    imports.head.doc shouldBe defined
    val tasks = imports.head.doc.get.elements.collect {
      case x: Task => x
    }
    tasks.size shouldBe 75
  }

  it should "handle optionals" in {
    val doc = parser.parseDocument(getTaskSource("missing_type_bug.wdl"))
    doc.version.value shouldBe WdlVersion.V1
  }

  it should "parse GATK tasks" in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/a576e26b11219c3d176d83375c648972410626f1/tasks/JointGenotypingTasks.wdl"
    val FileSource = fileResolver.resolve(url)
    val doc = parser.parseDocument(FileSource)

    doc.version.value shouldBe WdlVersion.V1
  }

  it should "parse GATK joint genotyping workflow" taggedAs Edge in {
    val url =
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/a576e26b11219c3d176d83375c648972410626f1/JointGenotyping.wdl"
    val FileSource = fileResolver.resolve(url)
    val doc = parser.parseDocument(FileSource)

    doc.version.value shouldBe WdlVersion.V1
  }

  it should "handle the meta section" taggedAs Edge in {
    val doc = parser.parseDocument(getTaskSource("meta_null_value.wdl"))
    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val task = doc.elements.head.asInstanceOf[Task]

    task.parameterMeta.get shouldBe a[ParameterMetaSection]
    task.parameterMeta.get.kvs.size shouldBe 1
    val mpkv = task.parameterMeta.get.kvs.head
    mpkv should matchPattern {
      case MetaKV("i", MetaValueNull(_), _) =>
    }
  }

  it should "complex meta values" taggedAs Edge in {
    val doc = parser.parseDocument(getTaskSource("meta_section_compound.wdl"))
    doc.version.value shouldBe WdlVersion.V1
    doc.elements.size shouldBe 1
    val task = doc.elements.head.asInstanceOf[Task]

    task.parameterMeta.get shouldBe a[ParameterMetaSection]
    task.parameterMeta.get.kvs.size shouldBe 3
  }

  it should "report errors in meta section" taggedAs Edge in {
    assertThrows[SyntaxException] {
      parser.parseDocument(getTaskSource("meta_section_error.wdl"))
    }
  }

  it should "logs duplicate key in runtime section " taggedAs Edge in {
    // TODO: test that logger issues warning
    parser.parseDocument(getTaskSource("runtime_section_duplicate_key.wdl"))
  }

  it should "parse a WDL string" in {
    val wdl =
      """version 1.0
        |task foo {
        |  command {
        |    echo 'hello'
        |  }
        |}
        |""".stripMargin
    val parsers = Parsers(
        followImports = false,
        fileResolver,
        logger = logger
    )
    val src = StringFileNode(wdl)
    val parser = parsers.getParser(src)
    val doc = parser.parseDocument(src)
    doc.version should matchPattern {
      case Version(WdlVersion.V1, _) =>
    }
    doc.elements.size shouldBe 1
    doc.elements.head.asInstanceOf[Task].name shouldBe "foo"
  }
}
