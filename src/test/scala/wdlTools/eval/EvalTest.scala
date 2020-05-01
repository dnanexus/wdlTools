package wdlTools.eval

import java.nio.file.{Files, Path, Paths}

import org.scalatest.{FlatSpec, Inside, Matchers}
import wdlTools.eval.WdlValues._
import wdlTools.syntax.{AbstractSyntax => AST}
import wdlTools.syntax.v1.ParseAll
import wdlTools.util.{EvalConfig, Options, Util => UUtil, Verbosity}
import wdlTools.typing.{Context => TypeContext, Stdlib => TypeStdlib, TypeChecker}

class EvalTest extends FlatSpec with Matchers with Inside {
  private val srcDir = Paths.get(getClass.getResource("/eval/v1").getPath)
  private val opts =
    Options(antlr4Trace = false, localDirectories = Vector(srcDir), verbosity = Verbosity.Quiet)
  private val parser = ParseAll(opts)
  private val stdlib = TypeStdlib(opts)
  private val checker = TypeChecker(stdlib)

  def safeMkdir(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    } else {
      // Path exists, make sure it is a directory, and not a file
      if (!Files.isDirectory(path))
        throw new Exception(s"Path ${path} exists, but is not a directory")
    }
  }

  private lazy val evalCfg: EvalConfig = {
    val baseDir = Paths.get("/tmp/evalTest")
    val homeDir = baseDir.resolve("home")
    val tmpDir = baseDir.resolve("tmp")
    for (d <- Vector(baseDir, homeDir, tmpDir))
      safeMkdir(d)
    val stdout = baseDir.resolve("stdout")
    val stderr = baseDir.resolve("stderr")
    EvalConfig(homeDir, tmpDir, stdout, stderr)
  }

  def parseAndTypeCheck(file: Path): (AST.Document, TypeContext) = {
    val doc = parser.parseDocument(UUtil.pathToURL(file))
    val typeCtx = checker.apply(doc)
    (doc, typeCtx)
  }

  it should "handle simple expressions" in {
    val file = srcDir.resolve("simple_expr.wdl")
    val (doc, typeCtx) = parseAndTypeCheck(file)
    val evaluator = Eval(opts,
                         evalCfg,
                         typeCtx.structs,
                         wdlTools.syntax.WdlVersion.V1,
                         Some(opts.getURL(file.toString)))

    doc.workflow should not be empty
    val wf = doc.workflow.get

    val decls: Vector[AST.Declaration] = wf.body.collect {
      case x: AST.Declaration => x
    }.toVector

    val ctxEnd = evaluator.applyDeclarations(decls, Context(Map.empty))
    val bindings = ctxEnd.bindings
    bindings("k0") shouldBe WV_Int(-1)
    bindings("k1") shouldBe WV_Int(1)

    bindings("b1") shouldBe WV_Boolean(true || false)
    bindings("b2") shouldBe WV_Boolean(true && false)
    bindings("b3") shouldBe WV_Boolean(10 == 3)
    bindings("b4") shouldBe WV_Boolean(4 < 8)
    bindings("b5") shouldBe WV_Boolean(4 >= 8)
    bindings("b6") shouldBe WV_Boolean(4 != 8)
    bindings("b7") shouldBe WV_Boolean(4 <= 8)
    bindings("b8") shouldBe WV_Boolean(11 > 8)

    // Arithmetic
    bindings("i1") shouldBe WV_Int(3 + 4)
    bindings("i2") shouldBe WV_Int(3 - 4)
    bindings("i3") shouldBe WV_Int(3 % 4)
    bindings("i4") shouldBe WV_Int(3 * 4)
    bindings("i5") shouldBe WV_Int(3 / 4)

    bindings("l0") shouldBe WV_String("a")
    bindings("l1") shouldBe WV_String("b")

    // pairs
    bindings("l") shouldBe WV_String("hello")
    bindings("r") shouldBe WV_Boolean(true)

    // structs
    bindings("pr1") shouldBe WV_Struct("Person",
                                       Map(
                                           "name" -> WV_String("Jay"),
                                           "city" -> WV_String("SF"),
                                           "age" -> WV_Int(31)
                                       ))
    bindings("name") shouldBe WV_String("Jay")
  }

  it should "call stdlib" taggedAs (Edge) in {
    val file = srcDir.resolve("stdlib.wdl")
    val (doc, typeCtx) = parseAndTypeCheck(file)
    val evaluator = Eval(opts,
                         evalCfg,
                         typeCtx.structs,
                         wdlTools.syntax.WdlVersion.V1,
                         Some(opts.getURL(file.toString)))

    doc.workflow should not be empty
    val wf = doc.workflow.get

    val decls: Vector[AST.Declaration] = wf.body.collect {
      case x: AST.Declaration => x
    }.toVector

    val ctxEnd = evaluator.applyDeclarations(decls, Context(Map.empty))
    val bd = ctxEnd.bindings

    bd("x") shouldBe (WV_Float(1.4))
    bd("n1") shouldBe (WV_Int(1))
    bd("n2") shouldBe (WV_Int(2))
    bd("n3") shouldBe (WV_Int(1))
    bd("cities2") shouldBe(WV_Array(Vector(WV_String("LA"),
                                           WV_String("Seattle"),
                                           WV_String("San Francisco"))))

    bd("table2") shouldBe(WV_Array(Vector(
                                     WV_Array(Vector(WV_String("A"), WV_String("allow"))),
                                     WV_Array(Vector(WV_String("B"), WV_String("big"))),
                                     WV_Array(Vector(WV_String("C"), WV_String("clam"))))))
    bd("m2") shouldBe(WV_Map(Map(WV_String("name") -> WV_String("hawk"),
                                 WV_String("kind") -> WV_String("bird"))))

    // sub
    bd("sentence1") shouldBe(WV_String("She visited three places on his trip: Aa, Ab, C, D, and E"))
    bd("sentence2") shouldBe(WV_String("He visited three places on his trip: Berlin, Berlin, C, D, and E"))
    bd("sentence3") shouldBe(WV_String("H      : A, A, C, D,  E"))

    // transpose
    bd("ar3") shouldBe(WV_Array(Vector(WV_Int(0), WV_Int(1), WV_Int(2))))
    bd("ar_ar2") shouldBe(WV_Array(Vector(
                                        WV_Array(Vector(WV_Int(1), WV_Int(4))),
                                        WV_Array(Vector(WV_Int(2), WV_Int(5))),
                                        WV_Array(Vector(WV_Int(3), WV_Int(6)))
                                   )))

    // zip
    bd("zlf") shouldBe(WV_Array(Vector(
                                  WV_Pair(WV_String("A"), WV_Boolean(true)),
                                  WV_Pair(WV_String("B"), WV_Boolean(false)),
                                  WV_Pair(WV_String("C"), WV_Boolean(true))
                                )))

    // cross
    inside(bd("cln")) {
      case WV_Array(vec) =>
        // the order of the cross product is unspecified
        Vector(WV_Pair(WV_String("A"), WV_Int(1)),
               WV_Pair(WV_String("B"), WV_Int(1)),
               WV_Pair(WV_String("C"), WV_Int(1)),
               WV_Pair(WV_String("A"), WV_Int(13)),
               WV_Pair(WV_String("B"), WV_Int(13)),
               WV_Pair(WV_String("C"), WV_Int(13))).foreach{
          case pair =>
            vec contains pair
        }
    }

    bd("l1") shouldBe(WV_Int(2))
    bd("l2") shouldBe(WV_Int(6))

    bd("files2") shouldBe WV_Array(Vector(WV_File("A"),
                                          WV_File("B"),
                                          WV_File("C"),
                                          WV_File("G"),
                                          WV_File("J"),
                                          WV_File("K")))


    bd("pref2") shouldBe WV_Array(Vector(WV_String("i_1"),
                                         WV_String("i_3"),
                                         WV_String("i_5"),
                                         WV_String("i_7")))
    bd("pref3") shouldBe WV_Array(Vector(WV_String("sub_1.0"),
                                         WV_String("sub_3.4"),
                                         WV_String("sub_5.1")))

    bd("sel1") shouldBe WV_String("A")
    bd("sel2") shouldBe WV_String("Henry")
  }
}
