version 1.0

task bar {
  input {
    Int i
    String s
    Float x
    Boolean b
    File f

    Pair[Int, String] p1
    Pair[Float, File] p2
    Pair[Boolean, Boolean] p3

    Array[Int] ia
    Array[String] sa
    Array[Float] xa
    Array[Boolean] ba
    Array[File] fa

    Map[Int, String] m_si
    Map[File, File] m_si
    Map[Boolean, Float] m_si
  }
}

workflow biz {
  input { String s }
  call bar as boz { input: i = s }
}
