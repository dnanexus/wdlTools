package wdlTools.types

import java.nio.file.{Path, Paths}

import dx.util.{FileSourceResolver, Logger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge
import wdlTools.syntax.Parsers
import wdlTools.types.WdlTypes.T_Function1
import wdlTools.types.{TypedAbstractSyntax => TAT}

import scala.collection.Map
import scala.collection.immutable.SeqMap

class TypeInferTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Normal
  private val draft2Dir = Paths.get(getClass.getResource("/types/draft2").getPath)
  private val v1Dir = Paths.get(getClass.getResource("/types/v1").getPath)
  private val v2Dir = Paths.get(getClass.getResource("/types/v2").getPath)
  private val v1StructsDir =
    Paths.get(getClass.getResource("/types/v1/structs").getPath)
  private val v1ImportsDir =
    Paths.get(getClass.getResource("/types/v1/imports").getPath)
  private val v2StructsDir =
    Paths.get(getClass.getResource("/types/v2/structs").getPath)

  private def check(dir: Path,
                    file: String,
                    udfs: Vector[UserDefinedFunctionPrototype] = Vector.empty,
                    substituteFunctionsForTasks: Boolean = false,
                    importDirs: Set[Path] = Set.empty): TAT.Document = {
    val fileResolver = FileSourceResolver.create((importDirs + dir).toVector)
    val checker = TypeInfer(userDefinedFunctions = udfs,
                            substituteFunctionsForTasks = substituteFunctionsForTasks,
                            fileResolver = fileResolver,
                            logger = logger)
    val sourceFile = fileResolver.fromPath(dir.resolve(file))
    val doc = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)._1
  }

  "TypeInfer" should "handle a directory output" in {
    check(v2Dir, "apps_1421_dir_output.wdl")
  }

  it should "handle a conditional in draft2 workflow" in {
    check(draft2Dir, "conditional.wdl")
  }

  it should "handle several struct definitions" taggedAs Edge in {
    check(v1StructsDir, "file3.wdl")
  }

  it should "handle struct aliases" in {
    check(v2StructsDir, "parent_workflow.wdl")
  }

  it should "handle reserved words as struct members" in {
    check(v2StructsDir, "keyword_in_struct.wdl")
  }

  it should "handle concatenation of different types in string interpolation" in {
    check(v2Dir, "add_int_and_string.wdl")
  }

  it should "handle concatenation of optional types in string the command block" in {
    check(v2Dir, "concat_optional_types_in_command.wdl")
  }

  it should "handle string interpolation in runtime" in {
    check(v2Dir, "runtime_interpolation.wdl")
  }

  it should "handle struct literals" in {
    val tDoc = check(v2StructsDir, "struct_literal.wdl")
    val task = tDoc.elements.collect {
      case task: TAT.Task => task
    }.head
    task.outputs.size shouldBe 1
    task.outputs.head match {
      case TAT.OutputParameter("name", WdlTypes.T_Struct("Name", types), expr) =>
        val typesMap = Map(
            "first" -> WdlTypes.T_String,
            "last" -> WdlTypes.T_String
        )
        types shouldBe typesMap
        expr match {
          case TAT.ExprObject(members, WdlTypes.T_Struct("Name", types)) =>
            types shouldBe typesMap
            val objMembers = members.map {
              case (TAT.ValueString(key, WdlTypes.T_String, _), value) => key -> value
              case other =>
                throw new Exception(s"invalid object key ${other}")
            }
            objMembers.keySet shouldBe Set("first", "last")
            objMembers("first") should matchPattern {
              case TAT.ExprIdentifier("first", WdlTypes.T_String) =>
            }
            objMembers("last") should matchPattern {
              case TAT.ExprIdentifier("last", WdlTypes.T_String) =>
            }
          case _ =>
            throw new Exception("invalid expression for output 'name'")
        }
      case _ =>
        throw new Exception("invalid OutputParameter for 'name'")
    }
  }

  it should "compare identical callables as equal" in {
    val tDoc = check(v1Dir, "top_level.wdl")
    val topImports = tDoc.elements.collect {
      case TAT.ImportDoc(namespace, _, _, doc) => namespace -> doc
    }.toMap
    topImports.size shouldBe 2
    val subImports = topImports("sub").elements.collect {
      case TAT.ImportDoc(namespace, _, _, doc) => namespace -> doc
    }.toMap
    subImports.size shouldBe 1
    val topTasks = topImports("tasks").elements.collect {
      case task: TAT.Task => task
    }
    topTasks.size shouldBe 1
    val subTasks = subImports("tasks").elements.collect {
      case task: TAT.Task => task
    }
    subTasks.size shouldBe 1
    topTasks.head shouldBe subTasks.head
  }

  it should "handle import in different folder" in {
    check(v1Dir, "import_folder.wdl", importDirs = Set(v1ImportsDir))
  }

  it should "allow UDFs" in {
    // should fail to type-check if the function is not defined
    assertThrows[TypeException] {
      check(v1Dir, "udfs.wdl")
    }

    object SayHelloFunction extends UserDefinedFunctionPrototype {

      private lazy val proto: T_Function1 =
        WdlTypes.T_Function1("say_hello", WdlTypes.T_String, WdlTypes.T_String)

      /**
        * Returns a prototype for a user-defined function matching
        * the given name and input types, or None if this UDF doesn't
        * match the signature.
        *
        * @param funcName   function name
        * @param inputTypes input types
        * @return
        */
      override def getPrototype(funcName: String,
                                inputTypes: Vector[WdlTypes.T]): Option[WdlTypes.T_Function] = {
        (funcName, inputTypes) match {
          case ("say_hello", Vector(WdlTypes.T_String)) => Some(proto)
          case _                                        => None
        }
      }

      /**
        * Returns a prototype for a user-defined function that can
        * substitute for a task.
        *
        * @param taskName task name
        * @param input    mapping of task input name to (type, optional)
        * @param output   mapping of task output name to type
        * @return
        */
      override def getTaskProxyFunction(
          taskName: String,
          input: SeqMap[String, (WdlTypes.T, Boolean)],
          output: SeqMap[String, WdlTypes.T]
      ): Option[(WdlTypes.T_Function, Vector[String])] = {
        (taskName, input.values.toVector, output.values.toVector) match {
          case ("say_hello", Vector((WdlTypes.T_String, false)), Vector(WdlTypes.T_String)) =>
            Some((proto, Vector(input.head._1)))
          case _ => None
        }
      }
    }
    val tDoc =
      check(v1Dir, "udfs.wdl", Vector(SayHelloFunction), substituteFunctionsForTasks = true)
    val say_hello_task = tDoc.elements
      .collectFirst {
        case task: TAT.Task if task.name == "say_hello" => task
      }
      .getOrElse(throw new Exception("missing task say_hello"))
    say_hello_task.wdlType.function shouldBe Some(
        (WdlTypes.T_Function1("say_hello", WdlTypes.T_String, WdlTypes.T_String), Vector("name"))
    )
  }
}
