version 1.0

workflow foo {
  # constants
  Boolean flag = true
  Int i = 8
  Float x = 2.718
  String s = "hello world"
  Array[String] ar1 = ["A", "B", "C"]
  Map[String, Int] m1 = {"X": 1, "Y": 10}
  Pair[Int, Int] p = (1, 12)

  # evaluations
  Int j = 3 + 5
  Int k = i + 5
  String s2 = "hello" + " world"

  # reading a file from local disk
  File readme = "/tmp/readme.md"
  File? file2 = "/tmp/xxx.txt"
}
