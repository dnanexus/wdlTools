version 1.0

workflow pair {
  input {
    Pair[String, Pair[Int, Int]] p1
    Pair[String, Pair[Int, Int]] p2
  }

  # accessing members of a pair structure
  Pair[String, Pair[Int, Int]] p3 = ("Joe", (5, 8))

  Int i = p1.right.left
  Int j = p2.right.left
  Int k = p3.right.left
}
