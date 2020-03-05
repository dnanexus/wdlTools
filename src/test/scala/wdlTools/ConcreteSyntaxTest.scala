package dxWDL.language

import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}
import collection.JavaConverters._

class Antlr4Test extends FlatSpec with Matchers {

   // Ignore a value. This is useful for avoiding warnings/errors
  // on unused variables.
  private def ignoreValue[A](value: A): Unit = {}

  private def getWdlSource(fname: String): String = {
    val p : String = getClass.getResource(s"/${fname}").getPath
    val path : Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }

  ignore should "handle various types" in {
    val doc = ConcreteSyntax.apply(getWdlSource("types.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  ignore should "handle types and expressions" in {
    val doc = ConcreteSyntax.apply(getWdlSource("expressions.wdl"))

    // TODO: add assertions here, to check the correct output
    ignoreValue(doc)
  }

  /*
  // A task that counts how many lines a file has
  val doc_task_wc =
    """|version 1.0
       |
       |task wc {
       |  input {
       |    File inp_file
       |  }
       |  # Just a random declaration
       |  Int i = 4 + 5
       |
       |  output {
       |    # Int num_lines = read_int(stdout())
       |    Int num_lines = 3
       |  }
       |  command {
       |    wc -l ~{inp_file}
       |  }
       |  meta {
       |    author : "Robin Hood"
       |  }
       |  parameterMeta {
       |    reason : "just because"
       |  }
       |}
       |""".stripMargin

  // A task that counts how many lines a file has
  val doc_task_wc_II =
    """|version 1.0
       |
       |task wc {
       |  input {
       |    File inp_file
       |  }
       |
       |  output {
       |    Int num_lines = 3
       |  }
       |  command {
       |    wc -l ~{inp_file}
       |  }
       |}
       |""".stripMargin
   */

  val doc_task_with_output_section =
    """|version 1.0
       |
       |task wc {
       |  output {
       |    Int num_lines = 3
       |  }
       |}
       |""".stripMargin

  it should "parse a task" in {
    val doc = ConcreteSyntax.apply(doc_task_with_output_section)
    System.out.println(doc)
  }
}
