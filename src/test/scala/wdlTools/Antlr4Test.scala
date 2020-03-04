package dxWDL.language

import org.scalatest.{FlatSpec, Matchers}

class Antlr4Test extends FlatSpec with Matchers {

  val doc_types =
    """|version 1.0
       |
       |task bar {
       |    input {
       |      Int i
       |      String s
       |      Float x
       |      Boolean b
       |      File f
       |
       |      Pair[Int, String] p1
       |      Pair[Float, File] p2
       |      Pair[Boolean, Boolean] p3
       |
       |      Array[Int] ia
       |      Array[String] sa
       |      Array[Float] xa
       |      Array[Boolean] ba
       |      Array[File] fa
       |
       |      Map[Int, String] m_si
       |      Map[File, File] m_si
       |      Map[Boolean, Float] m_si
       |    }
       |
       |    command <<<
       |        echo ~{i}
       |    >>>
       |}
       |
       |workflow biz {
       |    input { String s }
       |    call bar as boz { input: i = s }
       |}
       |""".stripMargin

  ignore should "handle various types" in {
    val doc = ConcreteAST.apply(doc_types)
    System.out.println(doc)
  }

  // These are various expressions
  val doc_expressions =
    """|version 1.0
       |
       |
       |task district {
       |  Int i = 3
       |  String s = "hello world"
       |  Float x = 4.3
       |  File f = "/dummy/x.txt"
       |  Boolean b = false
       |
       |  # Logical expressions
       |  Boolean b2 = true || false
       |  Boolean b3 = true && false
       |  Boolean b4 = 3 == 5
       |  Boolean b5 = 4 < 5
       |  Boolean b6 = 4 >= 5
       |  Boolean b7 = 6 != 7
       |  Boolean b8 = 6 <= 7
       |  Boolean b9 = 6 > 7
       |  Boolean b10 = !b2
       |
       |  # Arithmetic
       |  Int j = 4 + 5
       |  Int j1 = 4 % 10
       |  Int j2 = 10 / 7
       |  Int j3 = j
       |  Int j4 = j + 19
       |
       |  Array[Int] ia = [1, 2, 3]
       |  Int k = ia[3]
       |  Int k2 = f(1, 2, 3)
       |  Map[Int, String] = {1 : "a", 2: "b"}
       |  Int k3 = if (true) then 1 else 2
       |  Int k4 = x.a
       |  Object o = { A : 1, B : 2 }
       |  Pair[Int, String] twenty_threes = (23, "twenty-three")
       |}
       |""".stripMargin

  it should "handle expressions" in {
    val doc = ConcreteAST.apply(doc_expressions)
    System.out.println(doc)
  }
}
